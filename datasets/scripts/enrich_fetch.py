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

Subcommands (each bounded by --max-records = maximum HTTP requests this run):

  crossref  GET https://api.crossref.org/works/{doi} per DOI. Follows
            redirects (301/302/303/307/308; the 2026-11-22 run lacked this, so
            alias DOIs came back as bare 301s). Retries 429/5xx/network errors
            with exponential backoff honouring Retry-After. 404 is final.
            Saves <out-root>/retrieved=<date>/<sha256(doi)>.json verbatim and
            appends <logs-dir>/crossref-<date>.tsv rows:
            doi, http_status, utc, sha256(doi), attempts, redirected, final_doi, note
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

  orcid     GET https://pub.orcid.org/v3.0/<orcid>/record (public API, JSON) for
            iDs from orcid-candidates.txt in file order (fetch priority written
            by enrich_reconcile.py). Saves <out-root>/retrieved=<date>/<orcid>.json.
            Skips iDs with an existing parseable record in any retrieved=*/.
            Log: <logs-dir>/orcid-<date>.tsv rows: orcid, http_status, utc, orcid, attempts, note

Exit codes: 0 ok (or dry run); 2 usage error / missing contact env var;
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
SCRIPT_VERSION = "1.0.0"
CONTACT_ENV = "SCHOLARCHAIN_CONTACT_EMAIL"
CROSSREF_BASE = "https://api.crossref.org/works/"
OPENALEX_BASE = "https://api.openalex.org/works"
ORCID_BASE = "https://pub.orcid.org/v3.0/"
EXIT_QUOTA = 75
REDACTED = b"<redacted-contact>"
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


# ---------------------------------------------------------------------------
# HTTP layer (replaceable in tests; never used without --confirm-network)
# ---------------------------------------------------------------------------


class _Redirects(urllib.request.HTTPRedirectHandler):
    """Follow 301/302/303/307 and also 308 (not followed by urllib on Python < 3.11)."""

    def http_error_308(self, req, fp, code, msg, headers):
        return self.http_error_301(req, fp, code, msg, headers)


def http_get(url, headers, timeout=60):
    """Return (status, body_bytes, final_url, retry_after_seconds_or_None). status 0 = network error."""
    opener = urllib.request.build_opener(_Redirects())
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
        ra = e.headers.get("Retry-After") if e.headers else None
        try:
            ra = float(ra) if ra is not None else None
        except ValueError:
            ra = None
        return e.code, body, url, ra
    except (urllib.error.URLError, OSError) as e:  # DNS, reset, timeout
        return 0, str(e).encode("utf-8", "replace"), url, None


class Client(object):
    def __init__(self, contact, getter=http_get, sleep=time.sleep, min_interval=0.2, retries=5, backoff=2.0):
        self.contact = contact
        self.getter = getter
        self.sleep = sleep
        self.min_interval = min_interval
        self.retries = retries
        self.backoff = backoff
        self.requests = 0
        self._last = None

    def headers(self, accept="application/json"):
        return {"User-Agent": "ScholarChain-dataset-fetch/%s (mailto:%s)" % (SCRIPT_VERSION, self.contact),
                "Accept": accept}

    def get(self, url, accept="application/json", final_statuses=(200, 404, 410)):
        """GET with retry/backoff. Returns (status, body, final_url, attempts)."""
        attempt = 0
        while True:
            attempt += 1
            if self._last is not None and self.min_interval > 0:
                wait = self.min_interval - (time.time() - self._last)
                if wait > 0:
                    self.sleep(wait)
            self.requests += 1
            status, body, final, retry_after = self.getter(url, self.headers(accept))
            self._last = time.time()
            if status in final_statuses:
                return status, body, final, attempt
            retryable = status == 0 or status == 429 or 500 <= status < 600
            if not retryable or attempt > self.retries:
                return status, body, final, attempt
            delay = retry_after if retry_after is not None else self.backoff ** attempt
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


def is_quota(status, body):
    if status == 429:
        return True
    return status in (402, 403) and bool(_QUOTA_RE.search(body or b""))


# ---------------------------------------------------------------------------
# Subcommands
# ---------------------------------------------------------------------------


def cmd_crossref(args, client):
    dois = read_input(args.input)
    out_dir = os.path.join(args.out_root, "retrieved=%s" % args.date)
    log_path = os.path.join(args.logs_dir, "crossref-%s.tsv" % args.date)
    todo = [d for d in dois if not existing_any(args.out_root, ec.doi_file_stem(d) + ".json", crossref_ok)]
    plan = todo[:args.max_records]
    print("crossref: input=%d already_saved=%d to_fetch_this_run=%d (bound --max-records=%d) out=%s"
          % (len(dois), len(dois) - len(todo), len(plan), args.max_records, ec.display_path(out_dir)))
    if client is None:
        print("DRY RUN: no request made (add --confirm-network to fetch)")
        return 0
    n_ok = 0
    for doi in plan:
        url = CROSSREF_BASE + urllib.parse.quote(doi, safe="")
        status, body, final, attempts = client.get(url)
        stem = ec.doi_file_stem(doi)
        if is_quota(status, body):
            append_log(log_path, [doi, status, ec.utc_now(), stem, attempts, "", "", "rate-limited; stopped"])
            raise QuotaStop("Crossref rate limit after %d requests; re-run the same command later to resume" % client.requests)
        note = ""
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
    print("openalex: input=%d batches=%d complete=%d to_fetch_this_run=%d batches (bound --max-records=%d) out=%s"
          % (len(dois), len(batches), len(batches) - len(todo), len(plan), args.max_records, ec.display_path(out_dir)))
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
            if client.requests >= args.max_records and page > 1:
                notes.append("page bound reached; incomplete")
                break
            status, body, _, _ = client.get(openalex_url(kind, b, args.per_page, page))
            if is_quota(status, body):
                append_log(log_path, [name, status, ec.utc_now(), len(b), pages, "quota; stopped"])
                raise QuotaStop("OpenAlex quota/rate limit reached after %d requests; re-run later to resume" % client.requests)
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
    todo = [i for i in ids if not existing_any(args.out_root, i + ".json", orcid_ok)]
    plan = todo[:args.max_records]
    print("orcid: input=%d already_saved=%d to_fetch_this_run=%d (bound --max-records=%d) out=%s"
          % (len(ids), len(ids) - len(todo), len(plan), args.max_records, ec.display_path(out_dir)))
    if client is None:
        print("DRY RUN: no request made (add --confirm-network to fetch)")
        return 0
    saved = 0
    for oid in plan:
        status, body, _, attempts = client.get(ORCID_BASE + oid + "/record", accept="application/json",
                                               final_statuses=(200, 404, 409, 410))
        if is_quota(status, body):
            append_log(log_path, [oid, status, ec.utc_now(), oid, attempts, "rate-limited; stopped"])
            raise QuotaStop("ORCID rate limit after %d requests; re-run later to resume" % client.requests)
        note = ""
        if status == 200:
            body, red = client.redact(body)
            note = "contact-redacted" if red else ""
            save_bytes(os.path.join(out_dir, oid + ".json"), body)
            saved += 1
        append_log(log_path, [oid, status, ec.utc_now(), oid, attempts, note])
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
                       help="maximum HTTP requests (crossref/orcid) or batches (openalex) this run (default %(default)s)")
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
        client = Client(contact, getter=getter, sleep=sleep, min_interval=args.min_interval, retries=args.retries)
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
