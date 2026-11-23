#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""enrich_reconcile.py -- offline reconciliation, DOI match measurement and ORCID candidates.

Owner: dataset-engineer (datasets/**)

No network access. Standard library only (Python >= 3.8). Deterministic: the
data outputs depend only on the input files (timestamps appear only in the
provenance sidecars).

Inputs (all local; raw and derived trees are gitignored):
  --selection-dir   datasets/derived/dblp  (selection-<label>.{tsv,dois.txt,xml} + sidecars, dblp.dtd)
  --crossref-root   datasets/raw/crossref  (retrieved=<date>/<sha256(doi)>.json)
  --openalex-root   datasets/raw/openalex  (retrieved=<date>/<chunk>.json [+ <chunk>.page-<n>.json])
  --logs-dir        datasets/raw/logs      (<source>-<date>.tsv fetch logs)
  --request-dir     datasets/derived/enrichment (crossref-dois.txt, oa-chunk-* request lists)

Tasks:
  1. Reconcile every fetch log against the saved responses (row/file counts,
     HTTP status codes, missing files, orphan files, duplicate rows,
     unparseable JSON, truncated OpenAlex pages, responses whose DOI does not
     match the request).
  2. Measure distinct canonical DOI matches for each DBLP selection, keeping
     FETCHED counts (HTTP 200 + file present) separate from VERIFIED matches
     (parsed response whose canonical DOI equals the requested DOI).
  3. Derive ORCID candidates from verified Crossref (author[].ORCID) and
     OpenAlex (authorships[].author.orcid) records, normalise and checksum
     them (ISO 7064 MOD 11-2), link each back to the selected DBLP works by
     DOI, and apply a surname heuristic against the DBLP author list.

Outputs (in --out-dir, default datasets/derived/enrichment, each with a
<file>.provenance.json sidecar): reconcile-summary.json,
crossref-doi-status.tsv, openalex-doi-status.tsv, crossref-retry-dois.txt,
crossref-404-dois.txt, openalex-retry-dois.txt, orcid-candidate-links.tsv,
orcid-candidates.tsv, orcid-candidates.txt.

Exit codes: 0 ok; 2 usage error; 3 missing input; 4 input integrity failure
(selection sidecar mismatch); 1 other error.
"""

from __future__ import print_function

import argparse
import collections
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dblp_select as ds  # noqa: E402
import enrich_common as ec  # noqa: E402

SCRIPT_NAME = "enrich_reconcile.py"
SCRIPT_VERSION = "1.0.0"

# Crossref per-DOI categories, best first (a later retrieval can only improve a DOI's state).
CR_ORDER = ("verified", "doi_mismatch", "envelope_error", "unparseable", "missing_file",
            "http_error", "not_in_log")
# OpenAlex per-DOI categories, best first.
OA_ORDER = ("verified", "alias_title_match", "unknown_truncated", "not_found", "chunk_failed", "not_in_any_chunk")

_AUTHOR_TAG_RE = re.compile(rb"<author\b([^>]*)>")
_ORCID_ATTR_RE = re.compile(rb"""\borcid\s*=\s*["']([^"']+)["']""")


class MissingInput(ec.EnrichError):
    exit_code = 3


def log(msg):
    ds.log(msg)


# ---------------------------------------------------------------------------
# DBLP selections
# ---------------------------------------------------------------------------


def load_selections(selection_dir, labels):
    """({label: {doi: key}}, refs, {doi: title}) from selection-<label>.tsv, after sidecar checks."""
    out = {}
    refs = []
    titles = {}
    for label in labels:
        tsv = os.path.join(selection_dir, "selection-%s.tsv" % label)
        dois = os.path.join(selection_dir, "selection-%s.dois.txt" % label)
        for p in (tsv, dois):
            if not os.path.isfile(p):
                raise MissingInput("missing selection file %s" % p)
            try:
                ds.check_sidecar(p)
            except ds.ValidationError as e:
                raise ec.EnrichValidationError(str(e))
            refs.append(ec.file_ref(p))
        rows = ds.read_tsv(tsv)
        m = {}
        for key, row in rows.items():
            m[row[4]] = key
            titles[row[4]] = row[6]
        listed = set(ec.read_lines(dois))
        if listed != set(m):
            raise ec.EnrichValidationError("%s: DOI list differs from TSV" % label)
        out[label] = m
    return out, refs, titles


def load_dblp_authors(xml_path):
    """{key: [(author name, dblp orcid or None), ...]} parsed with the local DTD."""
    authors = {}
    problems = []

    def on_record(stream, rec):
        key = rec.attrs.get("key")
        names = rec.fields.get("author", [])
        raw = stream.raw(rec)
        tags = _AUTHOR_TAG_RE.findall(raw)
        orcids = []
        for attrs in tags:
            m = _ORCID_ATTR_RE.search(attrs)
            orcids.append(ec.normalize_orcid(m.group(1).decode("latin-1"))[0] if m else None)
        if len(orcids) != len(names):
            problems.append(key)
            orcids = [None] * len(names)
        authors[key] = list(zip(names, orcids))

    dtd = os.path.join(os.path.dirname(os.path.abspath(xml_path)), ds.RAW_DTD_NAME)
    stream = ds.DblpRecordStream(dtd, on_record)
    with open(xml_path, "rb") as f:
        stream.parse_file(f)
    return authors, problems


# ---------------------------------------------------------------------------
# Logs
# ---------------------------------------------------------------------------


def read_log(path):
    """Return (rows, malformed) where rows = [(col1, status_str, ts, col4, extra...)]."""
    rows = []
    malformed = 0
    with open(path, "r", encoding="utf-8", newline="") as f:
        for line in f:
            line = line.rstrip("\n").rstrip("\r")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) < 4:
                malformed += 1
                continue
            rows.append(parts)
    return rows, malformed


# ---------------------------------------------------------------------------
# Crossref
# ---------------------------------------------------------------------------


def classify_crossref_file(path, requested):
    """(category, response_doi, message_or_None)."""
    obj, err = ec.load_json_file(path)
    if err is not None:
        return "unparseable", "", None
    if not isinstance(obj, dict) or obj.get("status") != "ok" or obj.get("message-type") != "work" \
            or not isinstance(obj.get("message"), dict):
        return "envelope_error", "", None
    msg = obj["message"]
    rdoi = ec.canonical_response_doi(msg.get("DOI"))
    if rdoi is None:
        return "envelope_error", "", None
    if rdoi != requested:
        return "doi_mismatch", rdoi, None
    return "verified", rdoi, msg


def reconcile_crossref(root, logs_dir, input_list_path, keep_messages_for):
    """Reconcile all crossref retrievals. Returns (per_run, per_doi, messages, input_check)."""
    runs = ec.retrieval_dirs(root)
    per_run = []
    per_doi = {}  # doi -> dict(category, http_status, retrieved, file, response_doi)
    messages = {}  # doi -> (message, file path) for verified DOIs in keep_messages_for

    def better(doi, rec):
        cur = per_doi.get(doi)
        if cur is None or CR_ORDER.index(rec["category"]) <= CR_ORDER.index(cur["category"]):
            per_doi[doi] = rec

    for date, rdir in runs:
        log_path = os.path.join(logs_dir, "crossref-%s.tsv" % date)
        files = set(n for n in os.listdir(rdir) if n.endswith(".json"))
        other_files = sorted(n for n in os.listdir(rdir) if not n.endswith(".json"))
        stats = collections.OrderedDict()
        stats["retrieved"] = date
        stats["response_dir"] = ec.display_path(rdir)
        stats["log"] = ec.display_path(log_path) if os.path.isfile(log_path) else None
        stats["response_files"] = len(files)
        stats["non_json_files"] = len(other_files)
        if not os.path.isfile(log_path):
            stats["status"] = "LOG MISSING"
            per_run.append(stats)
            continue
        rows, malformed = read_log(log_path)
        stats["log_rows"] = len(rows)
        stats["log_malformed_rows"] = malformed
        status_counts = collections.Counter(r[1] for r in rows)
        stats["log_status_counts"] = dict(sorted(status_counts.items()))
        doi_rows = collections.Counter(r[0] for r in rows)
        stats["log_distinct_dois"] = len(doi_rows)
        stats["log_duplicate_doi_rows"] = sum(c - 1 for c in doi_rows.values() if c > 1)
        stats["log_hash_column_mismatch"] = sum(1 for r in rows if r[3] != ec.doi_file_stem(r[0]))
        ts = sorted(r[2] for r in rows)
        stats["log_first_utc"] = ts[0] if ts else None
        stats["log_last_utc"] = ts[-1] if ts else None
        last = {}
        for r in rows:  # last row per DOI wins
            last[r[0]] = r
        expected_files = set(ec.doi_file_stem(d) + ".json" for d in last)
        stats["orphan_response_files"] = len(files - expected_files)
        cat = collections.Counter()
        mismatches = []
        non200_with_file = 0
        for doi in sorted(last):
            r = last[doi]
            fn = ec.doi_file_stem(doi) + ".json"
            path = os.path.join(rdir, fn)
            has_file = fn in files
            rec = {"http_status": r[1], "retrieved": date, "file": "", "response_doi": ""}
            if r[1] != "200":
                if has_file:
                    non200_with_file += 1
                rec["category"] = "http_error"
            elif not has_file:
                rec["category"] = "missing_file"
            else:
                c, rdoi, msg = classify_crossref_file(path, doi)
                rec["category"] = c
                rec["response_doi"] = rdoi
                rec["file"] = os.path.join(stats["response_dir"], fn)
                if c == "doi_mismatch":
                    mismatches.append([doi, rdoi])
                if c == "verified" and doi in keep_messages_for:
                    messages[doi] = (msg, path)
            cat[rec["category"]] += 1
            better(doi, rec)
        stats["fetched_http200_with_file"] = sum(1 for d, r in last.items()
                                                 if r[1] == "200" and ec.doi_file_stem(d) + ".json" in files)
        stats["non200_rows_with_file"] = non200_with_file
        stats["per_doi_category_counts"] = dict(sorted(cat.items()))
        stats["doi_mismatch_examples"] = mismatches[:20]
        stats["http_error_dois_by_status"] = dict(
            (s, sorted(d for d, r in last.items() if r[1] == s)) for s in sorted(status_counts) if s != "200")
        per_run.append(stats)

    input_check = None
    if input_list_path and os.path.isfile(input_list_path):
        inp = ec.read_lines(input_list_path)
        logged = set(per_doi)
        input_check = {
            "path": ec.display_path(input_list_path),
            "sha256": ec.sha256_file(input_list_path),
            "lines": len(inp),
            "distinct": len(set(inp)),
            "input_not_in_any_log": len(set(inp) - logged),
            "logged_not_in_input": len(logged - set(inp)),
        }
        for d in set(inp) - logged:
            per_doi[d] = {"category": "not_in_log", "http_status": "", "retrieved": "", "file": "", "response_doi": ""}
    return per_run, per_doi, messages, input_check


# ---------------------------------------------------------------------------
# OpenAlex
# ---------------------------------------------------------------------------


def echoed_request_dois(meta):
    """Canonical DOIs echoed in meta.x_query.oqo (None when absent)."""
    try:
        rows = meta["x_query"]["oqo"]["filter_rows"]
    except (KeyError, TypeError):
        return None
    out = set()
    for fr in rows:
        for f in fr.get("filters", []) if isinstance(fr, dict) else []:
            if isinstance(f, dict) and f.get("column_id") == "doi":
                c = ec.canonical_response_doi(f.get("value"))
                if c:
                    out.add(c)
    return out


def chunk_pages(rdir, name, files):
    pages = []
    if name + ".json" in files:
        pages.append(os.path.join(rdir, name + ".json"))
    extra = sorted((int(m.group(1)), n) for n in files
                   for m in [re.match(r"^%s\.page-([0-9]+)\.json$" % re.escape(name), n)] if m)
    pages += [os.path.join(rdir, n) for _, n in extra]
    return pages


def reconcile_openalex(root, logs_dir, request_dir, keep_results_for, titles=None):
    runs = ec.retrieval_dirs(root)
    per_run = []
    per_doi = {}  # doi -> dict(category, chunk, retrieved, works, work_ids, alias_doi)
    titles = titles or {}
    results = {}  # doi -> list of (work dict, file path)

    def better(doi, rec):
        cur = per_doi.get(doi)
        if cur is None or OA_ORDER.index(rec["category"]) <= OA_ORDER.index(cur["category"]):
            per_doi[doi] = rec

    requested_all = set()
    for date, rdir in runs:
        log_path = os.path.join(logs_dir, "openalex-%s.tsv" % date)
        files = set(n for n in os.listdir(rdir) if n.endswith(".json"))
        stats = collections.OrderedDict()
        stats["retrieved"] = date
        stats["response_dir"] = ec.display_path(rdir)
        stats["log"] = ec.display_path(log_path) if os.path.isfile(log_path) else None
        stats["response_files"] = len(files)
        if not os.path.isfile(log_path):
            stats["status"] = "LOG MISSING"
            per_run.append(stats)
            continue
        rows, malformed = read_log(log_path)
        stats["log_rows"] = len(rows)
        stats["log_malformed_rows"] = malformed
        status_counts = collections.Counter(r[1] for r in rows)
        stats["log_status_counts"] = dict(sorted(status_counts.items()))
        names = collections.Counter(r[0] for r in rows)
        stats["log_duplicate_chunk_rows"] = sum(c - 1 for c in names.values() if c > 1)
        ts = sorted(r[2] for r in rows)
        stats["log_first_utc"] = ts[0] if ts else None
        stats["log_last_utc"] = ts[-1] if ts else None
        last = {}
        for r in rows:
            last[r[0]] = r
        claimed = set()
        c = collections.Counter()
        doi_chunks = collections.Counter()
        truncated = []
        echo_mismatch = []
        quota_like = []
        not_requested_results = 0
        results_without_doi = 0
        works_total = 0
        multi_work = 0
        alias_matches = 0
        for name in sorted(last):
            r = last[name]
            req_path = None
            for cand in (os.path.join(rdir, name + ".dois"), os.path.join(request_dir, name)):
                if os.path.isfile(cand):
                    req_path = cand
                    break
            pages = chunk_pages(rdir, name, files)
            for p in pages:
                claimed.add(os.path.basename(p))
            if req_path is None:
                c["request_file_missing"] += 1
                continue
            req = ec.read_lines(req_path)
            req_set = set(req)
            requested_all |= req_set
            for d in req_set:
                doi_chunks[d] += 1
            if str(len(req)) != r[3]:
                c["declared_count_mismatch"] += 1
            if len(req_set) != len(req):
                c["request_duplicate_lines"] += 1

            def mark(cat_name, works=0, wids=""):
                for d in sorted(req_set):
                    better(d, {"category": cat_name, "chunk": name, "retrieved": date, "works": works,
                               "work_ids": wids, "alias_doi": "", "file": ""})

            if r[1] in ("404", "410") and name.startswith("oa-single-"):
                c["single_not_found"] += 1
                mark("not_found")
                continue
            if r[1] != "200":
                c["chunk_http_error"] += 1
                mark("chunk_failed")
                continue
            if not pages:
                c["chunk_missing_file"] += 1
                mark("chunk_failed")
                continue
            got = []
            meta0 = None
            bad = False
            for p in pages:
                obj, err = ec.load_json_file(p)
                if isinstance(obj, dict) and "results" not in obj and "id" in obj and "doi" in obj:
                    obj = {"meta": {"count": 1}, "results": [obj]}  # single-work endpoint response
                if err is not None or not isinstance(obj, dict) or not isinstance(obj.get("results"), list):
                    bad = True
                    if isinstance(obj, dict) and ("error" in obj or "message" in obj) and "results" not in obj:
                        quota_like.append(name)
                    break
                if meta0 is None:
                    meta0 = obj.get("meta") or {}
                got += [(w, p) for w in obj["results"]]
            if bad:
                c["chunk_unparseable_or_error"] += 1
                mark("chunk_failed")
                continue
            c["chunk_parsed"] += 1
            echo = echoed_request_dois(meta0)
            if echo is None:
                c["request_echo_absent"] += 1
            elif echo != req_set:
                echo_mismatch.append(name)
            declared = meta0.get("count")
            is_trunc = isinstance(declared, int) and declared > len(got)
            if is_trunc:
                truncated.append([name, declared, len(got)])
            by_doi = collections.defaultdict(list)
            extras = []
            for w, p in got:
                works_total += 1
                wd = ec.canonical_response_doi(w.get("doi")) if isinstance(w, dict) else None
                if wd is None:
                    results_without_doi += 1
                    continue
                if wd not in req_set:
                    not_requested_results += 1
                    extras.append((w, wd))
                    continue
                by_doi[wd].append((w, p))
            for d in sorted(req_set):
                ws = by_doi.get(d)
                if ws:
                    if len(ws) > 1:
                        multi_work += 1
                    wids = ";".join(sorted(str(w.get("id", "")).rsplit("/", 1)[-1] for w, _ in ws))
                    better(d, {"category": "verified", "chunk": name, "retrieved": date, "works": len(ws),
                               "work_ids": wids, "alias_doi": "", "file": ec.display_path(ws[0][1])})
                    if d in keep_results_for:
                        results[d] = ws
                    continue
                t = ec.title_key(titles.get(d, ""))
                alias = sorted(set((str(w.get("id", "")).rsplit("/", 1)[-1], wd) for w, wd in extras
                                   if t and ec.title_key(w.get("title") or "") == t))
                if len(alias) == 1:
                    alias_matches += 1
                    better(d, {"category": "alias_title_match", "chunk": name, "retrieved": date, "works": 0,
                               "work_ids": alias[0][0], "alias_doi": alias[0][1], "file": ec.display_path(pages[0])})
                else:
                    better(d, {"category": "unknown_truncated" if is_trunc else "not_found", "chunk": name,
                               "retrieved": date, "works": 0, "work_ids": "", "alias_doi": "",
                               "file": ec.display_path(pages[0])})
        stats["orphan_response_files"] = len(files - claimed)
        stats["chunk_counts"] = dict(sorted(c.items()))
        stats["truncated_chunks"] = truncated
        stats["truncated_chunk_count"] = len(truncated)
        stats["request_echo_mismatch_chunks"] = echo_mismatch
        stats["error_body_chunks"] = quota_like
        stats["results_total"] = works_total
        stats["results_without_doi"] = results_without_doi
        stats["results_doi_not_in_request"] = not_requested_results
        stats["requested_dois_alias_title_match"] = alias_matches
        stats["requested_dois_with_multiple_works"] = multi_work
        stats["requested_dois_distinct"] = len(doi_chunks)
        stats["requested_dois_in_more_than_one_chunk"] = sum(1 for v in doi_chunks.values() if v > 1)
        per_run.append(stats)
    return per_run, per_doi, results, requested_all


# ---------------------------------------------------------------------------
# Match measurement
# ---------------------------------------------------------------------------


def match_table(sel_dois, cr_doi, oa_doi, cr_requested):
    sel = set(sel_dois)
    arxiv = set(d for d in sel if d.startswith(ec.ARXIV_DOI_PREFIX))
    cr_req = sel & cr_requested
    cr_cat = collections.Counter(cr_doi[d]["category"] for d in cr_req if d in cr_doi)
    cr_fetched = sum(1 for d in cr_req if d in cr_doi and cr_doi[d]["http_status"] == "200"
                     and cr_doi[d]["category"] != "missing_file")
    cr_ok = set(d for d in cr_req if d in cr_doi and cr_doi[d]["category"] == "verified")
    cr_http = collections.Counter(cr_doi[d]["http_status"] for d in cr_req
                                  if d in cr_doi and cr_doi[d]["category"] == "http_error")
    oa_req = set(d for d in sel if d in oa_doi and oa_doi[d]["category"] != "not_in_any_chunk")
    oa_cat = collections.Counter(oa_doi[d]["category"] for d in oa_req)
    oa_fetched = sum(1 for d in oa_req if oa_doi[d]["category"] != "chunk_failed")
    oa_ok = set(d for d in oa_req if oa_doi[d]["category"] == "verified")
    both = cr_ok & oa_ok
    union = cr_ok | oa_ok

    def pct(n, d):
        return round(100.0 * n / d, 2) if d else None

    return collections.OrderedDict([
        ("selected_distinct_dois", len(sel)),
        ("arxiv_10.48550_dois", len(arxiv)),
        ("crossref", collections.OrderedDict([
            ("requested", len(cr_req)),
            ("not_requested", len(sel - cr_req)),
            ("not_requested_arxiv", len((sel - cr_req) & arxiv)),
            ("fetched_http200_with_file", cr_fetched),
            ("verified_match", len(cr_ok)),
            ("verified_pct_of_selected", pct(len(cr_ok), len(sel))),
            ("verified_pct_of_requested", pct(len(cr_ok), len(cr_req))),
            ("category_counts", dict(sorted(cr_cat.items()))),
            ("http_error_status_counts", dict(sorted(cr_http.items()))),
        ])),
        ("openalex", collections.OrderedDict([
            ("requested", len(oa_req)),
            ("not_requested", len(sel - oa_req)),
            ("fetched_in_http200_parsed_chunk", oa_fetched),
            ("verified_match", len(oa_ok)),
            ("verified_pct_of_selected", pct(len(oa_ok), len(sel))),
            ("verified_arxiv", len(oa_ok & arxiv)),
            ("alias_title_match_not_counted_as_verified",
             sum(1 for d in oa_req if oa_doi[d]["category"] == "alias_title_match")),
            ("dois_with_multiple_works", sum(1 for d in oa_ok if oa_doi[d]["works"] > 1)),
            ("category_counts", dict(sorted(oa_cat.items()))),
        ])),
        ("union_verified", len(union)),
        ("union_verified_pct_of_selected", pct(len(union), len(sel))),
        ("intersection_verified", len(both)),
        ("crossref_only_verified", len(cr_ok - oa_ok)),
        ("openalex_only_verified", len(oa_ok - cr_ok)),
        ("neither_verified", len(sel - union)),
        ("neither_verified_arxiv", len((sel - union) & arxiv)),
    ])


# ---------------------------------------------------------------------------
# ORCID candidates
# ---------------------------------------------------------------------------


def orcid_links(sel50, sel10, cr_msgs, oa_results, dblp_authors):
    """List of link dicts (one per orcid x doi x source x author position)."""
    links = []
    for doi in sorted(cr_msgs):
        msg, path = cr_msgs[doi]
        key = sel50[doi]
        names = [a for a, _ in dblp_authors.get(key, [])]
        dblp_orcids = set(o for _, o in dblp_authors.get(key, []) if o)
        for pos, a in enumerate(msg.get("author") or []):
            if not isinstance(a, dict) or not a.get("ORCID"):
                continue
            raw = a.get("ORCID")
            norm, ok = ec.normalize_orcid(raw)
            fam = a.get("family") or a.get("name") or ""
            disp = (" ".join(x for x in (a.get("given"), a.get("family")) if x)) or a.get("name") or ""
            h, matched = ec.surname_heuristic(ec.name_tokens(fam), names)
            links.append({
                "orcid": norm or "", "orcid_raw": raw, "checksum_valid": ok, "doi": doi, "dblp_key": key,
                "in_10k": doi in sel10, "source": "crossref", "position": pos + 1, "source_name": disp,
                "name_heuristic": h, "dblp_author_matched": matched,
                "dblp_asserts_same_orcid": bool(norm) and norm in dblp_orcids,
                "authenticated": a.get("authenticated-orcid") is True,
                "response_file": ec.display_path(path),
            })
    for doi in sorted(oa_results):
        key = sel50[doi]
        names = [a for a, _ in dblp_authors.get(key, [])]
        dblp_orcids = set(o for _, o in dblp_authors.get(key, []) if o)
        for w, path in oa_results[doi]:
            for pos, au in enumerate(w.get("authorships") or []):
                author = au.get("author") if isinstance(au, dict) else None
                if not isinstance(author, dict) or not author.get("orcid"):
                    continue
                raw = author.get("orcid")
                norm, ok = ec.normalize_orcid(raw)
                disp = author.get("display_name") or au.get("raw_author_name") or ""
                h, matched = ec.surname_heuristic(ec.name_tokens(disp), names)
                links.append({
                    "orcid": norm or "", "orcid_raw": raw, "checksum_valid": ok, "doi": doi, "dblp_key": key,
                    "in_10k": doi in sel10, "source": "openalex", "position": pos + 1, "source_name": disp,
                    "name_heuristic": h, "dblp_author_matched": matched,
                    "dblp_asserts_same_orcid": bool(norm) and norm in dblp_orcids,
                    "authenticated": False,
                    "response_file": ec.display_path(path),
                })
    links.sort(key=lambda l: (l["orcid"], l["doi"], l["source"], l["position"], l["orcid_raw"]))
    return links


LINK_COLUMNS = ("orcid", "orcid_raw", "checksum_valid", "doi", "dblp_key", "in_10k", "source", "position",
                "source_name", "name_heuristic", "dblp_author_matched", "dblp_asserts_same_orcid",
                "authenticated", "response_file")
CAND_COLUMNS = ("priority", "orcid", "checksum_valid", "sources", "n_dblp_works", "n_dblp_works_10k",
                "n_links", "links_surname_exact", "links_surname_token", "links_name_none",
                "dblp_asserted_links", "dblp_keys", "names_seen")


def build_candidates(links):
    by = collections.OrderedDict()
    for l in links:
        k = l["orcid"] or ("INVALID:" + l["orcid_raw"])
        by.setdefault(k, []).append(l)
    cands = []
    for k, ls in by.items():
        valid = all(l["checksum_valid"] for l in ls) and not k.startswith("INVALID:")
        keys = sorted(set(l["dblp_key"] for l in ls))
        keys10 = sorted(set(l["dblp_key"] for l in ls if l["in_10k"]))
        h = collections.Counter(l["name_heuristic"] for l in ls)
        cands.append({
            "orcid": k, "checksum_valid": valid,
            "sources": "|".join(sorted(set(l["source"] for l in ls))),
            "n_dblp_works": len(keys), "n_dblp_works_10k": len(keys10), "n_links": len(ls),
            "links_surname_exact": h["surname_exact"], "links_surname_token": h["surname_token"],
            "links_name_none": h["none"],
            "dblp_asserted_links": sum(1 for l in ls if l["dblp_asserts_same_orcid"]),
            "dblp_keys": ";".join(keys),
            "names_seen": "|".join(sorted(set(l["source_name"] for l in ls if l["source_name"])))[:300],
        })
    # Fetch priority: valid first, then linked to the 10K selection, then any name agreement, then iD.
    cands.sort(key=lambda c: (not c["checksum_valid"], c["n_dblp_works_10k"] == 0,
                              (c["links_surname_exact"] + c["links_surname_token"]) == 0, c["orcid"]))
    for i, c in enumerate(cands, 1):
        c["priority"] = i
    return cands


def candidate_stats(links, cands, sel10, sel50, dblp_authors):
    valid = [c for c in cands if c["checksum_valid"]]
    dblp_orcid_10 = set()
    dblp_orcid_50 = set()
    for d, key in sel50.items():
        for _, o in dblp_authors.get(key, []):
            if o:
                dblp_orcid_50.add(o)
                if d in sel10:
                    dblp_orcid_10.add(o)
    cand_set = set(c["orcid"] for c in valid)
    by_src = collections.Counter(l["source"] for l in links)
    return collections.OrderedDict([
        ("links_total", len(links)),
        ("links_by_source", dict(sorted(by_src.items()))),
        ("links_checksum_invalid", sum(1 for l in links if not l["checksum_valid"])),
        ("links_name_heuristic", dict(sorted(collections.Counter(l["name_heuristic"] for l in links).items()))),
        ("links_dblp_asserts_same_orcid", sum(1 for l in links if l["dblp_asserts_same_orcid"])),
        ("crossref_links_authenticated_orcid", sum(1 for l in links if l["authenticated"])),
        ("distinct_orcids_all", len(cands)),
        ("distinct_orcids_checksum_valid", len(valid)),
        ("distinct_orcids_checksum_invalid", len(cands) - len(valid)),
        ("valid_linked_to_10k_work", sum(1 for c in valid if c["n_dblp_works_10k"] > 0)),
        ("valid_with_any_surname_agreement", sum(1 for c in valid if c["links_surname_exact"] + c["links_surname_token"] > 0)),
        ("valid_with_no_surname_agreement", sum(1 for c in valid if c["links_surname_exact"] + c["links_surname_token"] == 0)),
        ("valid_by_sources", dict(sorted(collections.Counter(c["sources"] for c in valid).items()))),
        ("dblp_asserted_orcids_50k", len(dblp_orcid_50)),
        ("dblp_asserted_orcids_10k", len(dblp_orcid_10)),
        ("dblp_asserted_orcids_50k_also_candidates", len(dblp_orcid_50 & cand_set)),
        ("dblp_works_50k_with_candidate", len(set(l["dblp_key"] for l in links if l["checksum_valid"]))),
        ("dblp_works_10k_with_candidate", len(set(l["dblp_key"] for l in links if l["checksum_valid"] and l["in_10k"]))),
        ("heuristic_note", "surname_exact/surname_token are a normalised-surname heuristic for data QA only; "
                           "they are not entity-resolution match decisions"),
    ])


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def parse_args(argv):
    ap = argparse.ArgumentParser(prog=SCRIPT_NAME, description=__doc__.split("\n")[0])
    ap.add_argument("--selection-dir", default="datasets/derived/dblp")
    ap.add_argument("--labels", default="10k,50k", help="selection labels; the last one is the superset used for links")
    ap.add_argument("--crossref-root", default="datasets/raw/crossref")
    ap.add_argument("--openalex-root", default="datasets/raw/openalex")
    ap.add_argument("--logs-dir", default="datasets/raw/logs")
    ap.add_argument("--request-dir", default="datasets/derived/enrichment")
    ap.add_argument("--crossref-input", default=None,
                    help="request list of the first Crossref run (default <request-dir>/crossref-dois.txt)")
    ap.add_argument("--out-dir", default="datasets/derived/enrichment")
    ap.add_argument("--root", default=None,
                    help="directory that recorded paths are relative to (default: git root)")
    return ap.parse_args(argv)


def run(argv):
    args = parse_args(argv)
    if args.root:
        ec.set_display_root(args.root)
    labels = [l.strip() for l in args.labels.split(",") if l.strip()]
    if len(labels) < 1:
        raise ec.EnrichError("need at least one label")
    sup = labels[-1]
    small = labels[0]
    for p in (args.selection_dir,):
        if not os.path.isdir(p):
            raise MissingInput("missing directory %s" % p)
    sels, sel_refs, titles = load_selections(args.selection_dir, labels)
    for l in labels[:-1]:
        if not set(sels[l]) <= set(sels[sup]):
            raise ec.EnrichValidationError("selection %s is not a subset of %s" % (l, sup))
    sel50 = sels[sup]
    sel10 = set(sels[small])

    sup_xml = os.path.join(args.selection_dir, "selection-%s.xml" % sup)
    ds.check_sidecar(sup_xml)
    log("parsing DBLP author lists from %s" % sup_xml)
    dblp_authors, author_problems = load_dblp_authors(sup_xml)

    cr_input = args.crossref_input or os.path.join(args.request_dir, "crossref-dois.txt")
    log("reconciling Crossref under %s" % args.crossref_root)
    cr_runs, cr_doi, cr_msgs, cr_input_check = reconcile_crossref(args.crossref_root, args.logs_dir, cr_input, set(sel50))
    log("reconciling OpenAlex under %s" % args.openalex_root)
    oa_runs, oa_doi, oa_results, oa_requested = reconcile_openalex(args.openalex_root, args.logs_dir,
                                                                   args.request_dir, set(sel50), titles)
    for d in sel50:
        if d not in oa_doi:
            oa_doi[d] = {"category": "not_in_any_chunk", "chunk": "", "retrieved": "", "works": 0, "work_ids": "",
                         "alias_doi": "", "file": ""}

    cr_requested = set(d for d, r in cr_doi.items())
    oa_req_vs_sel = {
        "requested_distinct": len(oa_requested),
        "requested_not_in_%s" % sup: len(oa_requested - set(sel50)),
        "%s_not_requested" % sup: len(set(sel50) - oa_requested),
    }
    cr_req_vs_sel = {
        "requested_distinct": len(cr_requested),
        "requested_not_in_%s" % sup: len(cr_requested - set(sel50)),
        "%s_not_requested" % sup: len(set(sel50) - cr_requested),
        "%s_not_requested_non_arxiv" % sup: len(set(d for d in sel50 if not d.startswith(ec.ARXIV_DOI_PREFIX))
                                                 - cr_requested),
    }

    matches = collections.OrderedDict()
    for l in labels:
        matches[l] = match_table(sels[l].keys(), cr_doi, oa_doi, cr_requested)

    links = orcid_links(sel50, sel10, dict((d, v) for d, v in cr_msgs.items() if cr_doi[d]["category"] == "verified"),
                        oa_results, dblp_authors)
    cands = build_candidates(links)
    cstats = candidate_stats(links, cands, sel10, sel50, dblp_authors)

    if not os.path.isdir(args.out_dir):
        os.makedirs(args.out_dir)
    o = lambda n: os.path.join(args.out_dir, n)  # noqa: E731

    def sel_cols(d):
        return [d in sel10, sel50.get(d, "")]

    cr_rows = []
    for d in sorted(set(cr_doi) | set(sel50)):
        r = cr_doi.get(d)
        if r is None:
            cat = "not_requested_arxiv" if d.startswith(ec.ARXIV_DOI_PREFIX) else "not_requested"
            r = {"category": cat, "http_status": "", "retrieved": "", "file": "", "response_doi": ""}
        cr_rows.append([d] + sel_cols(d) + [r["category"], r["http_status"], r["retrieved"], r["file"], r["response_doi"]])
    ec.write_tsv(o("crossref-doi-status.tsv"),
                 ("doi", "in_%s" % small, "dblp_key", "category", "last_http_status", "retrieved", "response_file", "response_doi"),
                 cr_rows)
    oa_rows = []
    for d in sorted(set(oa_doi) | set(sel50)):
        r = oa_doi[d]
        oa_rows.append([d] + sel_cols(d) + [r["category"], r["chunk"], r["retrieved"], r["works"], r["work_ids"],
                                            r["alias_doi"], r["file"]])
    ec.write_tsv(o("openalex-doi-status.tsv"),
                 ("doi", "in_%s" % small, "dblp_key", "category", "chunk", "retrieved", "n_works", "work_ids",
                  "alias_doi", "response_file"), oa_rows)

    cr_retry = sorted(d for d, r in cr_doi.items() if r["category"] != "verified"
                      and not (r["category"] == "http_error" and r["http_status"] == "404"))
    cr_404 = sorted(d for d, r in cr_doi.items() if r["category"] == "http_error" and r["http_status"] == "404")
    oa_retry = sorted(d for d, r in oa_doi.items() if r["category"] in ("unknown_truncated", "chunk_failed", "not_in_any_chunk"))
    for name, items in (("crossref-retry-dois.txt", cr_retry), ("crossref-404-dois.txt", cr_404),
                        ("openalex-retry-dois.txt", oa_retry)):
        ec.atomic_write_bytes(o(name), ("".join(x + "\n" for x in items)).encode("utf-8"))

    ec.write_tsv(o("orcid-candidate-links.tsv"), LINK_COLUMNS, [[l[c] for c in LINK_COLUMNS] for l in links])
    ec.write_tsv(o("orcid-candidates.tsv"), CAND_COLUMNS, [[c[k] for k in CAND_COLUMNS] for c in cands])
    valid_ids = [c["orcid"] for c in cands if c["checksum_valid"]]
    ec.atomic_write_bytes(o("orcid-candidates.txt"), ("".join(x + "\n" for x in valid_ids)).encode("utf-8"))

    summary = collections.OrderedDict([
        ("selection_labels", labels),
        ("development_run_note", "10K/50K are DEVELOPMENT runs; no reduced-final-scale approval exists"),
        ("dblp_author_lists_parsed", len(dblp_authors)),
        ("dblp_author_orcid_alignment_problems", len(author_problems)),
        ("crossref", collections.OrderedDict([
            ("runs", cr_runs), ("first_run_input_check", cr_input_check), ("requested_vs_selection", cr_req_vs_sel),
            ("retry_list", {"path": ec.display_path(o("crossref-retry-dois.txt")), "count": len(cr_retry)}),
            ("http404_list", {"path": ec.display_path(o("crossref-404-dois.txt")), "count": len(cr_404)}),
        ])),
        ("openalex", collections.OrderedDict([
            ("runs", oa_runs), ("requested_vs_selection", oa_req_vs_sel),
            ("retry_list", {"path": ec.display_path(o("openalex-retry-dois.txt")), "count": len(oa_retry)}),
        ])),
        ("matches", matches),
        ("orcid_candidates", cstats),
    ])
    ec.write_json(o("reconcile-summary.json"), summary)

    run_meta = {"script_name": SCRIPT_NAME, "script_version": SCRIPT_VERSION,
                "code_version": ec.code_version(), "generated_at_utc": ec.utc_now()}
    inputs = sel_refs + [ec.file_ref(sup_xml)]
    for date, _ in ec.retrieval_dirs(args.crossref_root):
        p = os.path.join(args.logs_dir, "crossref-%s.tsv" % date)
        if os.path.isfile(p):
            inputs.append(ec.file_ref(p))
    for date, _ in ec.retrieval_dirs(args.openalex_root):
        p = os.path.join(args.logs_dir, "openalex-%s.tsv" % date)
        if os.path.isfile(p):
            inputs.append(ec.file_ref(p))
    if os.path.isfile(cr_input):
        inputs.append(ec.file_ref(cr_input))
    inputs.append({"path": ec.display_path(args.crossref_root), "note": "per-DOI response files; see reconcile-summary.json runs"})
    inputs.append({"path": ec.display_path(args.openalex_root), "note": "chunk response files; see reconcile-summary.json runs"})
    desc = {
        "reconcile-summary.json": "fetch-log reconciliation, per-selection DOI match tables and ORCID candidate statistics",
        "crossref-doi-status.tsv": "one row per requested or selected DOI: best Crossref state across retrievals",
        "openalex-doi-status.tsv": "one row per requested or selected DOI: best OpenAlex state across retrievals",
        "crossref-retry-dois.txt": "requested DOIs without a verified Crossref response, excluding HTTP 404",
        "crossref-404-dois.txt": "requested DOIs for which Crossref returned HTTP 404",
        "openalex-retry-dois.txt": "selected DOIs whose OpenAlex state is unknown (truncated page, failed or missing chunk)",
        "orcid-candidate-links.tsv": "one row per ORCID x DOI x source x author position from verified records",
        "orcid-candidates.tsv": "distinct ORCID candidates with link counts and surname heuristic (QA only)",
        "orcid-candidates.txt": "checksum-valid ORCID candidates in fetch-priority order (input for enrich_fetch.py orcid)",
    }
    shas = {}
    for name, d in desc.items():
        src = "crossref+openalex" if name.startswith(("reconcile", "orcid")) else name.split("-")[0]
        shas[name] = ec.sidecar(o(name), inputs, {"description": d, "selection_labels": labels,
                                                  "rule": "see datasets/dataset-manifest.yaml enrichment section"},
                                run_meta, src)
    m = matches
    for l in labels:
        print("%s: selected=%d crossref fetched=%d verified=%d | openalex fetched=%d verified=%d | union=%d intersection=%d"
              % (l, m[l]["selected_distinct_dois"], m[l]["crossref"]["fetched_http200_with_file"],
                 m[l]["crossref"]["verified_match"], m[l]["openalex"]["fetched_in_http200_parsed_chunk"],
                 m[l]["openalex"]["verified_match"], m[l]["union_verified"], m[l]["intersection_verified"]))
    print("orcid candidates: distinct=%d valid=%d links=%d" % (cstats["distinct_orcids_all"],
                                                              cstats["distinct_orcids_checksum_valid"], cstats["links_total"]))
    print("summary=%s sha256=%s" % (ec.display_path(o("reconcile-summary.json")), shas["reconcile-summary.json"]))
    return 0


def main(argv=None):
    try:
        return run(sys.argv[1:] if argv is None else argv)
    except ds.SelectError as e:
        sys.stderr.write("ERROR: %s\n" % e)
        return e.exit_code
    except ec.EnrichError as e:
        sys.stderr.write("ERROR: %s\n" % e)
        return e.exit_code


if __name__ == "__main__":
    sys.exit(main())
