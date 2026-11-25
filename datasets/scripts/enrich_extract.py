#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""enrich_extract.py -- DOI-keyed JSONL extracts of the verified Crossref and OpenAlex records.

Owner: dataset-engineer (datasets/**)

No network access. Standard library only (Python >= 3.8). Deterministic: output
depends only on the input files (timestamps appear only in the sidecars).

Reads the per-DOI categories written by enrich_reconcile.py
(crossref-doi-status.tsv, openalex-doi-status.tsv) and, for every DOI whose
category is "verified" (provider record whose canonical DOI equals the
selected DBLP DOI), writes one flat JSON line per provider record to
datasets/derived/enrichment/:

  crossref-works.jsonl   one line per verified Crossref record
  openalex-works.jsonl   one line per verified OpenAlex work (a DOI can have >1 work)

Lines are sorted by (dblp_key, source_record_id). Every line carries its
provenance (retrieved date, repo-relative raw file, sha256 of that raw file).
Each output has a .provenance.json sidecar with line counts and sha256 values.
The extracts are DERIVED and gitignored; the record shapes are documented in
datasets/schemas/crossref-schema.md and openalex-schema.md ("Enrichment extracts").

Exit codes: 0 ok; 2 usage; 3 missing input; 4 raw record does not verify; 1 other.
"""

from __future__ import print_function

import argparse
import collections
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dblp_select as ds  # noqa: E402
import enrich_common as ec  # noqa: E402

SCRIPT_NAME = "enrich_extract.py"
SCRIPT_VERSION = "1.0.0"
SHAPE_VERSION = "1.0.0"


class VerifyError(ec.EnrichError):
    exit_code = 4


class MissingInput(ec.EnrichError):
    exit_code = 3


class UsageError(ec.EnrichError):
    exit_code = 2


def dumps(obj):
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def read_status(path):
    if not os.path.isfile(path):
        raise MissingInput("missing input %s (run enrich_reconcile.py first)" % path)
    with open(path, "r", encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        for line in f:
            yield dict(zip(header, line.rstrip("\n").split("\t")))


def repo_path(root, rel):
    return rel if os.path.isabs(rel) else os.path.join(root, rel)


def orcid_fields(raw):
    """(normalised iD or None, raw string or None). An iD that fails the ISO 7064 checksum is NOT normalised."""
    if not isinstance(raw, str) or not raw.strip():
        return None, None
    norm, ok = ec.normalize_orcid(raw)
    return (norm if ok else None), raw


def first_str(v):
    if isinstance(v, list):
        for x in v:
            if isinstance(x, str) and x.strip():
                return x.strip()
        return None
    return v.strip() if isinstance(v, str) and v.strip() else None


def crossref_year(msg):
    for k in ("issued", "published", "published-print", "published-online"):
        dp = (msg.get(k) or {}).get("date-parts")
        if isinstance(dp, list) and dp and isinstance(dp[0], list) and dp[0] and isinstance(dp[0][0], int):
            return dp[0][0]
    return None


def crossref_line(msg, doi, dblp_key, retrieved, raw_rel, raw_sha):
    authors = []
    for i, a in enumerate(msg.get("author") or [], 1):
        if not isinstance(a, dict):
            continue
        norm, raw = orcid_fields(a.get("ORCID"))
        authors.append({"position": i, "given": a.get("given"), "family": a.get("family"), "name": a.get("name"),
                        "sequence": a.get("sequence"), "orcid": norm, "orcid_raw": raw,
                        "orcid_authenticated": a.get("authenticated-orcid") if raw else None})
    return {"schema_version": SHAPE_VERSION, "source": "crossref", "source_record_id": "crossref:" + doi,
            "doi": doi, "dblp_key": dblp_key, "title": first_str(msg.get("title")), "year": crossref_year(msg),
            "venue": first_str(msg.get("container-title")) or first_str(msg.get("short-container-title")),
            "type": msg.get("type"), "publisher": msg.get("publisher"), "authors": authors,
            "retrieved": retrieved, "raw_file": raw_rel, "raw_sha256": raw_sha}


def openalex_line(w, doi, dblp_key, retrieved, raw_rel, raw_sha):
    wid = w.get("id") or ""
    authors = []
    for i, a in enumerate(w.get("authorships") or [], 1):
        au = a.get("author") or {}
        norm, raw = orcid_fields(au.get("orcid"))
        authors.append({
            "position": i, "author_position": a.get("author_position"), "raw_name": a.get("raw_author_name"),
            "display_name": au.get("display_name"), "author_id": au.get("id"), "orcid": norm, "orcid_raw": raw,
            "institutions": [{"id": x.get("id"), "display_name": x.get("display_name"), "ror": x.get("ror"),
                              "country_code": x.get("country_code")} for x in (a.get("institutions") or [])]})
    topics = []
    for t in w.get("topics") or []:
        topics.append({"id": t.get("id"), "display_name": t.get("display_name"), "score": t.get("score"),
                       "subfield": (t.get("subfield") or {}).get("display_name"),
                       "field": (t.get("field") or {}).get("display_name"),
                       "domain": (t.get("domain") or {}).get("display_name")})
    src = (w.get("primary_location") or {}).get("source") or {}
    return {"schema_version": SHAPE_VERSION, "source": "openalex", "source_record_id": "openalex:" + wid.rsplit("/", 1)[-1],
            "openalex_id": wid or None, "doi": doi, "dblp_key": dblp_key, "title": w.get("title"),
            "year": w.get("publication_year") if isinstance(w.get("publication_year"), int) else None,
            "venue": src.get("display_name"), "venue_id": src.get("id"), "venue_issn_l": src.get("issn_l"),
            "cited_by_count": w.get("cited_by_count"), "authors": authors, "topics": topics,
            "retrieved": retrieved, "raw_file": raw_rel, "raw_sha256": raw_sha}


def build_crossref(status_path, root):
    lines = []
    sha_cache = {}
    n_files = 0
    for row in read_status(status_path):
        if row["category"] != "verified":
            continue
        rel = row["response_file"]
        raw = repo_path(root, rel)
        obj, err = ec.load_json_file(raw)
        if err is not None or not isinstance(obj, dict) or not isinstance(obj.get("message"), dict):
            raise VerifyError("crossref raw file for %s does not parse: %s" % (row["doi"], err))
        if ec.canonical_response_doi(obj["message"].get("DOI")) != row["doi"]:
            raise VerifyError("crossref raw file for %s has a different DOI" % row["doi"])
        if rel not in sha_cache:
            sha_cache[rel] = ec.sha256_file(raw)
            n_files += 1
        lines.append(crossref_line(obj["message"], row["doi"], row["dblp_key"], row["retrieved"], rel, sha_cache[rel]))
    lines.sort(key=lambda r: (r["dblp_key"], r["source_record_id"]))
    return lines, n_files


def build_openalex(status_path, root):
    by_chunk = collections.defaultdict(list)
    for row in read_status(status_path):
        if row["category"] == "verified":
            by_chunk[row["response_file"]].append(row)
    lines = []
    for rel in sorted(by_chunk):
        raw = repo_path(root, rel)
        obj, err = ec.load_json_file(raw)
        if err is not None or not isinstance(obj, dict):
            raise VerifyError("openalex chunk %s does not parse: %s" % (rel, err))
        if "results" not in obj and "id" in obj:
            obj = {"results": [obj]}
        sha = ec.sha256_file(raw)
        works = collections.defaultdict(dict)
        for w in obj.get("results") or []:
            d = ec.canonical_response_doi(w.get("doi"))
            if d is not None and w.get("id"):
                works[d][w["id"]] = w  # dedupe by work id inside a chunk
        for row in by_chunk[rel]:
            found = works.get(row["doi"])
            if not found:
                raise VerifyError("openalex chunk %s has no work for verified DOI %s" % (rel, row["doi"]))
            for wid in sorted(found):
                lines.append(openalex_line(found[wid], row["doi"], row["dblp_key"], row["retrieved"], rel, sha))
    lines.sort(key=lambda r: (r["dblp_key"], r["source_record_id"]))
    return lines, len(by_chunk)


def summarize(lines):
    authors = sum(len(r["authors"]) for r in lines)
    with_orcid = sum(1 for r in lines for a in r["authors"] if a["orcid"])
    return {"lines": len(lines), "distinct_dois": len(set(r["doi"] for r in lines)),
            "distinct_source_record_ids": len(set(r["source_record_id"] for r in lines)),
            "author_mentions": authors, "author_mentions_with_orcid": with_orcid,
            "distinct_orcids": len(set(a["orcid"] for r in lines for a in r["authors"] if a["orcid"])),
            "lines_without_title": sum(1 for r in lines if not r["title"]),
            "lines_without_year": sum(1 for r in lines if r["year"] is None),
            "lines_without_venue": sum(1 for r in lines if not r["venue"]),
            "lines_without_authors": sum(1 for r in lines if not r["authors"])}


def write_jsonl(path, lines):
    ec.atomic_write_bytes(path, "".join(dumps(r) + "\n" for r in lines).encode("utf-8"))


def run(argv):
    ap = argparse.ArgumentParser(prog=SCRIPT_NAME, description=__doc__.split("\n")[0])
    ap.add_argument("--crossref-status", default="datasets/derived/enrichment/crossref-doi-status.tsv")
    ap.add_argument("--openalex-status", default="datasets/derived/enrichment/openalex-doi-status.tsv")
    ap.add_argument("--out-dir", default="datasets/derived/enrichment")
    ap.add_argument("--repo-root", default=None, help="root that raw_file paths are relative to (default: git root or cwd)")
    args = ap.parse_args(argv)
    root = args.repo_root or ds.repo_root() or os.getcwd()
    ec.set_display_root(root)
    if not os.path.isdir(args.out_dir):
        os.makedirs(args.out_dir)
    t0 = time.time()
    cr, cr_files = build_crossref(args.crossref_status, root)
    t1 = time.time()
    oa, oa_files = build_openalex(args.openalex_status, root)
    t2 = time.time()
    cr_path = os.path.join(args.out_dir, "crossref-works.jsonl")
    oa_path = os.path.join(args.out_dir, "openalex-works.jsonl")
    write_jsonl(cr_path, cr)
    write_jsonl(oa_path, oa)
    run_meta = {"script_name": SCRIPT_NAME, "script_version": SCRIPT_VERSION,
                "code_version": ec.code_version(), "generated_at_utc": ec.utc_now()}
    common_rule = ("one line per provider record whose DOI-verified category is 'verified' in %s; "
                   "lines sorted by (dblp_key, source_record_id); no network")
    out = {}
    for path, lines, status, label, nraw, secs in (
            (cr_path, cr, args.crossref_status, "crossref", cr_files, t1 - t0),
            (oa_path, oa, args.openalex_status, "openalex", oa_files, t2 - t1)):
        inputs = [ec.file_ref(status), {"note": "per-record raw_file + raw_sha256 are on every line; raw files read: %d" % nraw}]
        out[label] = ec.sidecar(path, inputs, {
            "description": "%s works extract for the Hadoop normalizers (flat, DOI-keyed JSON Lines)" % label,
            "selection_rule": common_rule % ec.display_path(status), "shape_version": SHAPE_VERSION,
            "counts": summarize(lines), "raw_files_read": nraw, "build_seconds": round(secs, 1),
            "synthetic": False, "development_run_note": "derived from the 50K DEVELOPMENT selection",
            "license": ("Crossref bibliographic metadata (abstracts, references, licence blocks dropped); terms to be "
                        "re-confirmed by the human" if label == "crossref" else
                        "OpenAlex data CC0 1.0 (https://openalex.org/about); abstract_inverted_index dropped"),
        }, run_meta, label)
    for label, path, lines in (("crossref", cr_path, cr), ("openalex", oa_path, oa)):
        print("%s: lines=%d sha256=%s -> %s" % (label, len(lines), out[label], ec.display_path(path)))
    print("seconds: crossref=%.1f openalex=%.1f" % (t1 - t0, t2 - t1))
    return 0


def main(argv=None):
    try:
        return run(sys.argv[1:] if argv is None else argv)
    except ec.EnrichError as e:
        sys.stderr.write("ERROR: %s\n" % e)
        return e.exit_code


if __name__ == "__main__":
    sys.exit(main())
