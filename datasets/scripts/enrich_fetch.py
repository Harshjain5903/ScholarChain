#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""enrich_fetch.py -- bounded, resumable Crossref / OpenAlex / ORCID retrieval for DBLP DOIs.

Owner: dataset-engineer (datasets/**)

SAFETY: this is the ONLY dataset script that talks to the network, and it does
so only when --confirm-network is given. Without it every subcommand is a
dry run: it reads the input list, applies skip-existing/resume logic and the
--max-records bound, prints the plan and exits 0 without any request.

Contact address: read at run time from the environment variable
SCHOLARCHAIN_CONTACT_EMAIL and sent only in the User-Agent header
("ScholarChain-dataset-fetch/<v> (mailto:<address>)"), which puts requests in
the Crossref and OpenAlex polite pools. The address is never written to logs,
file names or provenance. If a response body echoes it, the occurrence is
replaced with "<redacted-contact>" before saving and the log row says so.

Bounds (two independent limits, both enforced on every run):

  --max-records   records (crossref DOIs, orcid iDs) or batches (openalex) planned this run.
  --max-requests  hard budget of HTTP requests actually SENT this run. Every attempt
                  counts: the first request of a record or batch, every retry, every
                  redirect hop and every further OpenAlex page. Default:
                  max_records + max(5, ceil(max_records / 4)). The budget is checked
                  BEFORE each request; when it is spent the run stops cleanly (exit 0),
                  nothing partial is marked complete, an event
                  "budget-exhausted" is appended to <logs-dir>/fetch-runs-<date>.tsv
                  and re-running the same command resumes.

Redirects: never followed automatically. 301/302/303/307/308 are handled
manually and only when the Location target is https, on the default port, with
no userinfo, and its host is one of ALLOWED_REDIRECT_HOSTS (api.crossref.org,
api.openalex.org, pub.orcid.org); at most MAX_REDIRECT_HOPS hops. Any other
target is refused WITHOUT sending a request (so the contact header never leaves
for a foreign host or over plain http); the refusal is written to the source
log note and as a "redirect-refused" event to fetch-runs-<date>.tsv (scheme and
host only). Each followed hop is a request and counts against --max-requests.

Run event log <logs-dir>/fetch-runs-<date>.tsv rows:
  utc, source, event, requests_sent, max_requests, max_records, detail
  events: run-complete | budget-exhausted | quota-stop | redirect-refused

Subcommands:

  crossref  GET https://api.crossref.org/works/{doi} per DOI. Follows
            allowlisted same-API redirects (see above; the 2026-11-22 run lacked
            redirect handling, so alias DOIs came back as bare 301s). Retries
            429/5xx/network errors with exponential backoff honouring
            Retry-After. 404 is final.
            Saves <out-root>/retrieved=<date>/<sha256(doi)>.json verbatim and
            appends <logs-dir>/crossref-<date>.tsv rows:
            doi, http_status, utc, sha256(doi), attempts, redirected, final_doi, note
            (attempts = HTTP requests sent for this DOI, retries and redirect hops included)
            Skips DOIs that already have a parseable status=ok response in ANY
            retrieved=*/ directory (resume / skip-existing).

  openalex  GET https://api.openalex.org/works?filter=doi:<u1>|<u2>|...&per-page=200
            in batches (default 50 DOIs). Batch name oa-batch-<sha256(sorted DOIs)[:12]>,
            request list saved as <name>.dois, page 1 as <name>.json, further
            pages as <name>.page-<n>.json (never truncated silently). DOIs
            containing ',' or '|' (filter syntax characters) use the single-work
            endpoint /works/https://doi.org/<doi> (oa-single-<sha12>). Quota
            aware: HTTP 429, or 402/403 whose body mentions a budget/quota/limit,
            stops the run immediately with exit code 75; re-running resumes,
            skipping batches whose saved response is complete.
            Log: <logs-dir>/openalex-<date>.tsv rows: name, http_status, utc, n_dois, pages, note

  orcid     GET https://pub.orcid.org/v3.0/<orcid>/record (public API, Accept:
            application/json, no API key) for iDs from --input in file order (default
            orcid-candidates.txt, fetch priority written by enrich_reconcile.py; the
            bounded 2,000 subset is orcid-fetch-2000.txt from orcid_select.py).
            Saves <out-root>/retrieved=<date>/<sha256(orcid)>.json verbatim (same
            naming as crossref). Redirects, request budget, retries and contact
            handling are identical to crossref. 404 and 409 (locked/deprecated)
            and 410 are final. Skips iDs with an existing parseable record in any
            retrieved=*/ directory (resume / skip-existing).
            Log: <logs-dir>/orcid-<date>.tsv rows: orcid, http_status, utc, sha256(orcid), attempts, note

Exit codes: 0 ok (or dry run, or request budget exhausted); 2 usage error / missing contact env var;
3 missing input; 75 quota or rate limit reached (resume later); 1 other error.
"""

from __future__ import print_function

import argparse
import datetime
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import enrich_common as ec  # noqa: E402

SCRIPT_NAME = "enrich_fetch.py"
SCRIPT_VERSION = "1.1.0"
CONTACT_ENV = "SCHOLARCHAIN_CONTACT_EMAIL"
CROSSREF_BASE = "https://api.crossref.org/works/"
OPENALEX_BASE = "https://api.openalex.org/works"
ORCID_BASE = "https://pub.orcid.org/v3.0/"
EXIT_QUOTA = 75
REDACTED = b"<redacted-contact>"
REDIRECT_STATUSES = (301, 302, 303, 307, 308)
ALLOWED_REDIRECT_HOSTS = frozenset(["api.crossref.org", "api.openalex.org", "pub.orcid.org"])
MAX_REDIRECT_HOPS = 5
RUN_LOG = "fetch-runs-%s.tsv"
_QUOTA_RE = re.compile(rb"budget|quota|rate.?limit|too many requests|insufficient", re.IGNORECASE)
_EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


class FetchError(ec.EnrichError):
    exit_code = 1


class UsageError(ec.EnrichError):
    exit_code = 2


class MissingInput(ec.EnrichError):
    exit_code = 3


class QuotaStop(ec.EnrichError):
    exit_code = EXIT_QUOTA


class BudgetExhausted(ec.EnrichError):
    """Raised BEFORE a request would exceed --max-requests; callers stop cleanly (exit 0)."""
    exit_code = 0


# ---------------------------------------------------------------------------
# HTTP layer (replaceable in tests; never used without --confirm-network)
# ---------------------------------------------------------------------------


class _NoRedirects(urllib.request.HTTPRedirectHandler):
    """Never follow a redirect automatically: the 3xx surfaces as HTTPError and the
    Client decides against the allowlist (passing an instance of this subclass to
    build_opener also replaces urllib's default redirect handler)."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def http_get(url, headers, timeout=60):
    """Return (status, body_bytes, final_url, retry_after_seconds_or_None). status 0 = network error.

    Redirects are NOT followed: for 301/302/303/307/308 the third element is the absolute
    Location target ("" when the header is missing) and the Client applies the allowlist.
    """
    opener = urllib.request.build_opener(_NoRedirects())
    req = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with opener.open(req, timeout=timeout) as resp:
            return resp.getcode(), resp.read(), resp.geturl(), None
    except urllib.error.HTTPError as e:
        body = b""
        try:
            body = e.read()
        except Exception:
            pass
        if e.code in REDIRECT_STATUSES:
            loc = e.headers.get("Location") if e.headers else None
            return e.code, body, urllib.parse.urljoin(url, loc) if loc else "", None
        ra = e.headers.get("Retry-After") if e.headers else None
        try:
            ra = float(ra) if ra is not None else None
        except ValueError:
            ra = None
        return e.code, body, url, ra
    except (urllib.error.URLError, OSError) as e:  # DNS, reset, timeout
        return 0, str(e).encode("utf-8", "replace"), url, None


def redirect_refusal(target, allowed_hosts=ALLOWED_REDIRECT_HOSTS):
    """None when a redirect to `target` may be followed, else a short reason (no URL, no contact)."""
    if not target:
        return "missing Location"
    try:
        p = urllib.parse.urlsplit(target)
        port = p.port
    except ValueError:
        return "malformed Location"
    if p.scheme.lower() != "https":
        return "non-https scheme %s" % (p.scheme.lower() or "<none>")
    if p.username is not None or p.password is not None:
        return "userinfo in Location"
    if port not in (None, 443):
        return "non-default port"
    if (p.hostname or "").lower() not in allowed_hosts:
        return "host not allowlisted"
    return None


def _scheme_host(url):
    try:
        p = urllib.parse.urlsplit(url)
        return "%s://%s" % (p.scheme.lower() or "<none>", (p.hostname or "<none>").lower())
    except ValueError:
        return "<malformed>"


class Client(object):
    def __init__(self, contact, getter=http_get, sleep=time.sleep, min_interval=0.2, retries=5, backoff=2.0,
                 max_requests=None, allowed_hosts=ALLOWED_REDIRECT_HOSTS, max_hops=MAX_REDIRECT_HOPS):
        self.contact = contact
        self.getter = getter
        self.sleep = sleep
        self.min_interval = min_interval
        self.retries = retries
        self.backoff = backoff
        self.max_requests = max_requests  # None = unbounded (library use only; the CLI always sets it)
        self.allowed_hosts = frozenset(h.lower() for h in allowed_hosts)
        self.max_hops = max_hops
        self.requests = 0
        self.last_note = ""
        self.refusals = []  # (scheme://host, reason) of refused redirect targets, this run
        self._last = None

    def _send(self, url, accept):
        """The only place a request leaves: budget check first, then pacing, then the getter."""
        if self.max_requests is not None and self.requests >= self.max_requests:
            raise BudgetExhausted("request budget exhausted (--max-requests=%d, sent=%d); re-run the same "
                                  "command to resume" % (self.max_requests, self.requests))
        if self._last is not None and self.min_interval > 0:
            wait = self.min_interval - (time.time() - self._last)
            if wait > 0:
                self.sleep(wait)
        self.requests += 1
        try:
            return self.getter(url, self.headers(accept))
        finally:
            self._last = time.time()

    def headers(self, accept="application/json"):
        return {"User-Agent": "ScholarChain-dataset-fetch/%s (mailto:%s)" % (SCRIPT_VERSION, self.contact),
                "Accept": accept}

    def get(self, url, accept="application/json", final_statuses=(200, 404, 410)):
        """GET with retry/backoff and allowlisted manual redirects.

        Returns (status, body, final_url, requests_sent_for_this_call). Every request
        (first, retry, redirect hop) passes through _send and so through the budget;
        BudgetExhausted propagates to the caller. self.last_note describes a refused
        redirect or hop limit ("" otherwise). A refused redirect returns the 3xx status
        with final_url = the last URL actually requested.
        """
        self.last_note = ""
        tries = 0
        hops = 0
        sent = 0
        cur = url
        while True:
            tries += 1
            sent += 1
            status, body, final, retry_after = self._send(cur, accept)
            if status in REDIRECT_STATUSES:
                target = final if final and final != cur else ""
                why = redirect_refusal(target, self.allowed_hosts)
                if why is None and hops >= self.max_hops:
                    why = "redirect hop limit %d" % self.max_hops
                if why is not None:
                    dest = _scheme_host(target) if target else "<none>"
                    dest_b, _ = self.redact(dest.encode("utf-8"))
                    dest = dest_b.decode("utf-8", "replace")
                    self.refusals.append((dest, why))
                    self.last_note = "redirect-refused %s (%s)" % (dest, why)
                    return status, body, cur, sent
                hops += 1
                tries -= 1  # a followed hop is not a retry (it still counted against the budget)
                cur = target
                continue
            if status in final_statuses:
                return status, body, final or cur, sent
            retryable = status == 0 or status == 429 or 500 <= status < 600
            if not retryable or tries > self.retries:
                return status, body, final or cur, sent
            delay = retry_after if retry_after is not None else self.backoff ** tries
            self.sleep(min(delay, 300))

    def redact(self, body):
        """Replace any echo of the contact address in a response body."""
        if not self.contact:
            return body, False
        needles = {self.contact.encode("utf-8"), urllib.parse.quote(self.contact, safe="").encode("utf-8"),
                   urllib.parse.quote(self.contact).encode("utf-8")}
        hit = False
        for n in needles:
            if n and n in body:
                body = body.replace(n, REDACTED)
                hit = True
        return body, hit


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def today_utc():
    return datetime.datetime.utcnow().strftime("%Y-%m-%d")


def contact_from_env(required):
    v = os.environ.get(CONTACT_ENV, "").strip()
    if not v:
        if required:
            raise UsageError("environment variable %s is not set; it must hold the contact address for the "
                             "polite pools (it is never written to disk)" % CONTACT_ENV)
        return ""
    if not _EMAIL_RE.match(v):
        raise UsageError("%s does not look like an email address" % CONTACT_ENV)
    return v


def append_log(path, row):
    d = os.path.dirname(path)
    if d and not os.path.isdir(d):
        os.makedirs(d)
    with open(path, "a", encoding="utf-8") as f:
        f.write("\t".join(ec.clean_cell(c) for c in row) + "\n")


def save_bytes(path, data):
    d = os.path.dirname(path)
    if not os.path.isdir(d):
        os.makedirs(d)
    ec.atomic_write_bytes(path, data)


def read_input(path):
    if not os.path.isfile(path):
        raise MissingInput("missing input list %s (run enrich_reconcile.py first)" % path)
    seen = set()
    out = []
    for line in ec.read_lines(path):
        v = line.strip()
        if v and v not in seen:
            seen.add(v)
            out.append(v)
    return out


def crossref_ok(path):
    obj, err = ec.load_json_file(path)
    return err is None and isinstance(obj, dict) and obj.get("status") == "ok" and isinstance(obj.get("message"), dict)


def openalex_complete(paths):
    """True when the saved pages parse and hold at least meta.count results (or a single work object)."""
    if not paths:
        return False
    got = 0
    count = None
    for p in paths:
        obj, err = ec.load_json_file(p)
        if err is not None or not isinstance(obj, dict):
            return False
        if "results" not in obj and "id" in obj:  # single-work response
            return True
        if not isinstance(obj.get("results"), list):
            return False
        if count is None:
            count = (obj.get("meta") or {}).get("count")
        got += len(obj["results"])
    return isinstance(count, int) and got >= count


def orcid_ok(path):
    obj, err = ec.load_json_file(path)
    return err is None and isinstance(obj, dict) and ("orcid-identifier" in obj or "person" in obj)


def existing_any(root, filename, check):
    for _, d in ec.retrieval_dirs(root):
        p = os.path.join(d, filename)
        if os.path.isfile(p) and check(p):
            return p
    return None


def batch_name(prefix, dois):
    return "%s-%s" % (prefix, ec.sha256_text("\n".join(sorted(dois)))[:12])


def plan_openalex_batches(dois, batch_size):
    """Deterministic batches: sorted unique DOIs; filter-unsafe DOIs go to single requests."""
    singles = sorted(d for d in set(dois) if "," in d or "|" in d)
    rest = sorted(set(dois) - set(singles))
    batches = [("oa-batch", rest[i:i + batch_size]) for i in range(0, len(rest), batch_size)]
    batches += [("oa-single", [d]) for d in singles]
    return [(batch_name(p, b), p, b) for p, b in batches]


def openalex_url(kind, dois, per_page, page):
    if kind == "oa-single":
        return OPENALEX_BASE + "/" + urllib.parse.quote("https://doi.org/" + dois[0], safe=":/")
    flt = "doi:" + "|".join("https://doi.org/" + d for d in dois)
    return OPENALEX_BASE + "?" + urllib.parse.urlencode({"filter": flt, "per-page": per_page, "page": page})


def default_max_requests(max_records):
    """Safe default request budget: max_records plus 25% (at least 5) headroom for retries/redirects/pages."""
    return max_records + max(5, -(-max_records // 4))


def run_event(args, source, event, client, detail=""):
    """Append one row to <logs-dir>/fetch-runs-<date>.tsv (never contains the contact address)."""
    append_log(os.path.join(args.logs_dir, RUN_LOG % args.date),
               [ec.utc_now(), source, event, client.requests, args.max_requests, args.max_records, detail])


def note_refusal(args, source, client, item):
    if client.last_note.startswith("redirect-refused"):
        run_event(args, source, "redirect-refused", client, "%s: %s" % (item, client.last_note))


def is_quota(status, body):
    if status == 429:
        return True
    return status in (402, 403) and bool(_QUOTA_RE.search(body or b""))


# ---------------------------------------------------------------------------
# Subcommands
# ---------------------------------------------------------------------------


def budget_stop(args, source, client, detail, exc):
    run_event(args, source, "budget-exhausted", client, detail)
    print("STOPPED: %s" % exc)
    print("%s: requests=%d (budget exhausted; resumable)" % (source, client.requests))
    return 0


def cmd_crossref(args, client):
    dois = read_input(args.input)
    out_dir = os.path.join(args.out_root, "retrieved=%s" % args.date)
    log_path = os.path.join(args.logs_dir, "crossref-%s.tsv" % args.date)
    todo = [d for d in dois if not existing_any(args.out_root, ec.doi_file_stem(d) + ".json", crossref_ok)]
    plan = todo[:args.max_records]
    print("crossref: input=%d already_saved=%d to_fetch_this_run=%d (bound --max-records=%d --max-requests=%d) out=%s"
          % (len(dois), len(dois) - len(todo), len(plan), args.max_records, args.max_requests, ec.display_path(out_dir)))
    if client is None:
        print("DRY RUN: no request made (add --confirm-network to fetch)")
        return 0
    n_ok = 0
    for doi in plan:
        url = CROSSREF_BASE + urllib.parse.quote(doi, safe="")
        try:
            status, body, final, attempts = client.get(url)
        except BudgetExhausted as e:
            return budget_stop(args, "crossref", client, "next=%s" % ec.doi_file_stem(doi), e)
        stem = ec.doi_file_stem(doi)
        if is_quota(status, body):
            append_log(log_path, [doi, status, ec.utc_now(), stem, attempts, "", "", "rate-limited; stopped"])
            run_event(args, "crossref", "quota-stop", client, "http %s" % status)
            raise QuotaStop("Crossref rate limit after %d requests; re-run the same command later to resume" % client.requests)
        note_refusal(args, "crossref", client, stem)
        note = client.last_note
        final_doi = ""
        redirected = int(final.rstrip("/") != url.rstrip("/"))
        if status == 200:
            body, red = client.redact(body)
            note = "contact-redacted" if red else ""
            try:
                final_doi = (json.loads(body.decode("utf-8")).get("message") or {}).get("DOI", "")
            except (ValueError, UnicodeDecodeError, AttributeError):
                note = (note + " unparseable").strip()
            save_bytes(os.path.join(out_dir, stem + ".json"), body)
            n_ok += 1
        append_log(log_path, [doi, status, ec.utc_now(), stem, attempts, redirected, final_doi, note])
    run_event(args, "crossref", "run-complete", client, "saved=%d" % n_ok)
    print("crossref: requests=%d saved=%d log=%s" % (client.requests, n_ok, ec.display_path(log_path)))
    return 0


def cmd_openalex(args, client):
    dois = read_input(args.input)
    out_dir = os.path.join(args.out_root, "retrieved=%s" % args.date)
    log_path = os.path.join(args.logs_dir, "openalex-%s.tsv" % args.date)
    batches = plan_openalex_batches(dois, args.batch_size)
    todo = []
    for name, kind, b in batches:
        done = False
        for _, d in ec.retrieval_dirs(args.out_root):
            files = set(os.listdir(d))
            pages = [os.path.join(d, n) for n in sorted(files)
                     if n == name + ".json" or re.match(r"^%s\.page-[0-9]+\.json$" % re.escape(name), n)]
            if openalex_complete(pages):
                done = True
                break
        if not done:
            todo.append((name, kind, b))
    plan = todo[:args.max_records]
    print("openalex: input=%d batches=%d complete=%d to_fetch_this_run=%d batches (bound --max-records=%d "
          "--max-requests=%d) out=%s" % (len(dois), len(batches), len(batches) - len(todo), len(plan),
                                         args.max_records, args.max_requests, ec.display_path(out_dir)))
    if client is None:
        print("DRY RUN: no request made (add --confirm-network to fetch)")
        return 0
    for name, kind, b in plan:
        save_bytes(os.path.join(out_dir, name + ".dois"), ("".join(d + "\n" for d in b)).encode("utf-8"))
        page = 1
        pages = 0
        got = 0
        notes = []
        status = None
        while True:
            try:  # every page, page 1 included, goes through the request budget
                status, body, _, _ = client.get(openalex_url(kind, b, args.per_page, page))
            except BudgetExhausted as e:
                if pages:  # partial batch: logged as incomplete, refetched from page 1 on resume
                    notes.append("request budget exhausted; incomplete")
                    append_log(log_path, [name, status, ec.utc_now(), len(b), pages, " ".join(notes)])
                return budget_stop(args, "openalex", client, "batch=%s page=%d" % (name, page), e)
            if is_quota(status, body):
                append_log(log_path, [name, status, ec.utc_now(), len(b), pages, "quota; stopped"])
                run_event(args, "openalex", "quota-stop", client, "http %s batch=%s" % (status, name))
                raise QuotaStop("OpenAlex quota/rate limit reached after %d requests; re-run later to resume" % client.requests)
            if client.last_note:
                notes.append(client.last_note)
                note_refusal(args, "openalex", client, name)
            if status != 200:
                break
            body, red = client.redact(body)
            if red:
                notes.append("contact-redacted")
            fn = name + ".json" if page == 1 else "%s.page-%d.json" % (name, page)
            save_bytes(os.path.join(out_dir, fn), body)
            pages += 1
            try:
                obj = json.loads(body.decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                notes.append("unparseable")
                break
            if kind == "oa-single":
                break
            got += len(obj.get("results") or [])
            count = (obj.get("meta") or {}).get("count") or 0
            if got >= count or not obj.get("results"):
                break
            page += 1
        append_log(log_path, [name, status, ec.utc_now(), len(b), pages, " ".join(notes)])
    run_event(args, "openalex", "run-complete", client, "batches=%d" % len(plan))
    print("openalex: requests=%d log=%s" % (client.requests, ec.display_path(log_path)))
    return 0


def cmd_orcid(args, client):
    ids = []
    for v in read_input(args.input):
        norm, ok = ec.normalize_orcid(v)
        if not ok:
            raise UsageError("invalid ORCID iD in input: %r" % v)
        ids.append(norm)
    out_dir = os.path.join(args.out_root, "retrieved=%s" % args.date)
    log_path = os.path.join(args.logs_dir, "orcid-%s.tsv" % args.date)
    todo = [i for i in ids if not existing_any(args.out_root, ec.orcid_file_stem(i) + ".json", orcid_ok)]
    plan = todo[:args.max_records]
    print("orcid: input=%d already_saved=%d to_fetch_this_run=%d (bound --max-records=%d --max-requests=%d) out=%s"
          % (len(ids), len(ids) - len(todo), len(plan), args.max_records, args.max_requests, ec.display_path(out_dir)))
    if client is None:
        print("DRY RUN: no request made (add --confirm-network to fetch)")
        return 0
    saved = 0
    for oid in plan:
        try:
            status, body, _, attempts = client.get(ORCID_BASE + oid + "/record", accept="application/json",
                                                   final_statuses=(200, 404, 409, 410))
        except BudgetExhausted as e:
            return budget_stop(args, "orcid", client, "next=%s" % oid, e)
        if is_quota(status, body):
            append_log(log_path, [oid, status, ec.utc_now(), ec.orcid_file_stem(oid), attempts, "rate-limited; stopped"])
            run_event(args, "orcid", "quota-stop", client, "http %s" % status)
            raise QuotaStop("ORCID rate limit after %d requests; re-run later to resume" % client.requests)
        note_refusal(args, "orcid", client, oid)
        note = client.last_note
        if status == 200:
            body, red = client.redact(body)
            note = "contact-redacted" if red else ""
            save_bytes(os.path.join(out_dir, ec.orcid_file_stem(oid) + ".json"), body)
            saved += 1
        append_log(log_path, [oid, status, ec.utc_now(), ec.orcid_file_stem(oid), attempts, note])
    run_event(args, "orcid", "run-complete", client, "saved=%d" % saved)
    print("orcid: requests=%d saved=%d log=%s" % (client.requests, saved, ec.display_path(log_path)))
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_args(argv):
    ap = argparse.ArgumentParser(prog=SCRIPT_NAME, description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd")
    defaults = {
        "crossref": ("datasets/derived/enrichment/crossref-retry-dois.txt", "datasets/raw/crossref", 2000),
        "openalex": ("datasets/derived/enrichment/openalex-retry-dois.txt", "datasets/raw/openalex", 200),
        "orcid": ("datasets/derived/enrichment/orcid-candidates.txt", "datasets/raw/orcid", 5000),
    }
    for name, (inp, root, bound) in defaults.items():
        p = sub.add_parser(name)
        p.add_argument("--input", default=inp, help="input list (default %(default)s)")
        p.add_argument("--out-root", default=root, help="raw root (default %(default)s)")
        p.add_argument("--logs-dir", default="datasets/raw/logs")
        p.add_argument("--date", default=today_utc(), help="retrieval date label YYYY-MM-DD (default: today UTC)")
        p.add_argument("--max-records", type=int, default=bound,
                       help="maximum records (crossref/orcid) or batches (openalex) planned this run (default %(default)s)")
        p.add_argument("--max-requests", type=int, default=None,
                       help="hard budget of HTTP requests sent this run, counting retries, redirect hops and "
                            "pages (default: max_records + max(5, ceil(max_records/4)))")
        p.add_argument("--min-interval", type=float, default=0.2 if name != "orcid" else 0.1,
                       help="minimum seconds between requests (default %(default)s)")
        p.add_argument("--retries", type=int, default=5)
        p.add_argument("--confirm-network", action="store_true",
                       help="actually perform HTTP requests (otherwise dry run)")
        if name == "openalex":
            p.add_argument("--batch-size", type=int, default=50, help="DOIs per filter request (1..100)")
            p.add_argument("--per-page", type=int, default=200, help="results per page (1..200)")
    args = ap.parse_args(argv)
    if not args.cmd:
        ap.print_help()
        raise UsageError("choose a subcommand: crossref | openalex | orcid")
    if args.max_records <= 0:
        raise UsageError("--max-records must be positive")
    if args.max_requests is None:
        args.max_requests = default_max_requests(args.max_records)
    if args.max_requests <= 0:
        raise UsageError("--max-requests must be positive")
    if not re.match(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$", args.date):
        raise UsageError("--date must be YYYY-MM-DD")
    if args.cmd == "openalex" and not (1 <= args.batch_size <= 100 and 1 <= args.per_page <= 200):
        raise UsageError("--batch-size must be 1..100 and --per-page 1..200")
    return args


def run(argv, getter=http_get, sleep=time.sleep):
    args = parse_args(argv)
    client = None
    if args.confirm_network:
        contact = contact_from_env(required=True)
        client = Client(contact, getter=getter, sleep=sleep, min_interval=args.min_interval, retries=args.retries,
                        max_requests=args.max_requests)
    return {"crossref": cmd_crossref, "openalex": cmd_openalex, "orcid": cmd_orcid}[args.cmd](args, client)


def main(argv=None):
    try:
        return run(sys.argv[1:] if argv is None else argv)
    except ec.EnrichError as e:
        sys.stderr.write("ERROR: %s\n" % e)
        return e.exit_code
    except KeyboardInterrupt:
        sys.stderr.write("interrupted; re-run the same command to resume\n")
        return 130


if __name__ == "__main__":
    sys.exit(main())
