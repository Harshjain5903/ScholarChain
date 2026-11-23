#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""enrich_sample.py -- build or verify small, committed Crossref/OpenAlex extracts for the DBLP sample.

Owner: dataset-engineer (datasets/**)

No network access. Standard library only.

build:  for every record of the committed DBLP sample (default
        datasets/samples/dblp/dblp-sample-50.xml = the 50 records of
        selection-10k with the smallest sha256(seed|key)), take its VERIFIED
        Crossref response and VERIFIED OpenAlex work(s) (as classified by
        enrich_reconcile.py in crossref-doi-status.tsv / openalex-doi-status.tsv),
        trim them to identifier, bibliographic and author fields (abstracts,
        reference lists, licence/link blocks and other bulky fields dropped),
        and write JSON Lines with per-line provenance (raw file path, raw
        sha256, retrieval date, trim rule). Also writes the ORCID candidate
        links of those works. Every output gets a .provenance.json sidecar.

verify: re-reads each line's raw file, checks its sha256, re-applies the trim
        and requires byte-identical JSON; checks sidecar sha256 values. When the
        gitignored raw files are absent (clean clone) the per-line raw checks are
        reported as SKIPPED and only the sidecar checks run.

Exit codes: 0 ok; 2 usage; 3 missing input; 4 verification failure; 1 other.
"""

from __future__ import print_function

import argparse
import collections
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dblp_select as ds  # noqa: E402
import enrich_common as ec  # noqa: E402

SCRIPT_NAME = "enrich_sample.py"
SCRIPT_VERSION = "1.0.0"

CROSSREF_KEEP = ("DOI", "type", "title", "container-title", "short-container-title", "publisher", "member", "prefix",
                 "issued", "published", "ISSN", "ISBN", "volume", "issue", "page", "reference-count",
                 "is-referenced-by-count", "author")
CROSSREF_AUTHOR_KEEP = ("given", "family", "name", "sequence", "ORCID", "authenticated-orcid")
OPENALEX_KEEP = ("id", "doi", "title", "publication_year", "cited_by_count", "ids")
CROSSREF_TRIM_RULE = ("Crossref message fields kept: %s; author[] fields kept: %s; everything else dropped "
                      "(notably abstract, reference, license, link, relation, assertion)."
                      % (", ".join(CROSSREF_KEEP), ", ".join(CROSSREF_AUTHOR_KEEP)))
OPENALEX_TRIM_RULE = ("OpenAlex work fields kept: %s; authorships[] reduced to author_position, raw_author_name, "
                      "author{id,display_name,orcid}, institutions[]{id,display_name,ror,country_code}; "
                      "primary_location reduced to source{id,display_name,issn_l}; everything else dropped "
                      "(notably abstract_inverted_index and topics)." % ", ".join(OPENALEX_KEEP))
LICENSE_NOTE = {
    "crossref": "Crossref metadata: Crossref states bibliographic metadata are facts free to reuse without "
                "restriction; abstracts may be publisher copyright and are excluded here. Terms to be re-confirmed "
                "by the human at https://www.crossref.org/documentation/retrieve-metadata/rest-api/",
    "openalex": "OpenAlex data: CC0 1.0 (https://openalex.org/about). abstract_inverted_index excluded.",
    "orcid": "ORCID iDs and names as published in the Crossref/OpenAlex records above (public metadata).",
}


class VerifyError(ec.EnrichError):
    exit_code = 4


class MissingInput(ec.EnrichError):
    exit_code = 3


class UsageError(ec.EnrichError):
    exit_code = 2


SAMPLE_NAMES = ("crossref-sample.jsonl", "openalex-sample.jsonl", "orcid-candidate-links-sample.tsv")


def trim_crossref(msg):
    out = collections.OrderedDict()
    for k in CROSSREF_KEEP:
        if k not in msg:
            continue
        if k == "author":
            out[k] = [dict((ak, a[ak]) for ak in CROSSREF_AUTHOR_KEEP if ak in a) for a in msg[k] if isinstance(a, dict)]
        else:
            out[k] = msg[k]
    return out


def trim_openalex(w):
    out = collections.OrderedDict((k, w[k]) for k in OPENALEX_KEEP if k in w)
    auths = []
    for a in w.get("authorships") or []:
        au = a.get("author") or {}
        auths.append({
            "author_position": a.get("author_position"),
            "raw_author_name": a.get("raw_author_name"),
            "author": {"id": au.get("id"), "display_name": au.get("display_name"), "orcid": au.get("orcid")},
            "institutions": [{"id": i.get("id"), "display_name": i.get("display_name"), "ror": i.get("ror"),
                              "country_code": i.get("country_code")} for i in (a.get("institutions") or [])],
        })
    out["authorships"] = auths
    src = ((w.get("primary_location") or {}).get("source")) or {}
    out["primary_location"] = {"source": {"id": src.get("id"), "display_name": src.get("display_name"),
                                          "issn_l": src.get("issn_l")}} if src else None
    return out


def dumps(obj):
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def read_status(path):
    rows = {}
    with open(path, "r", encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        for line in f:
            parts = line.rstrip("\n").split("\t")
            rows[parts[0]] = dict(zip(header, parts))
    return rows


def repo_path(root, rel):
    return rel if os.path.isabs(rel) else os.path.join(root, rel)


def sample_keys(sample_xml):
    rows, _, _ = ds.parse_extract(sample_xml)
    return dict((row[4], key) for key, row in rows.items())  # doi -> key


def openalex_works(chunk_path, doi):
    obj, err = ec.load_json_file(chunk_path)
    if err is not None:
        raise VerifyError("%s: %s" % (chunk_path, err))
    if "results" not in obj and "id" in obj:
        obj = {"results": [obj]}
    return [w for w in obj.get("results") or [] if ec.canonical_response_doi(w.get("doi")) == doi]


def build(args, root):
    sample_xml = args.dblp_sample
    for p in (sample_xml, args.crossref_status, args.openalex_status, args.links):
        if not os.path.isfile(p):
            raise MissingInput("missing input %s" % p)
    existing = [n for n in SAMPLE_NAMES if os.path.exists(os.path.join(args.out_dir, n))]
    if existing and not args.force:
        raise UsageError("refusing to overwrite %s in %s; use --force" % (existing[0], args.out_dir))
    doi_key = sample_keys(sample_xml)
    cr = read_status(args.crossref_status)
    oa = read_status(args.openalex_status)
    os.makedirs(args.out_dir, exist_ok=True)
    cr_lines, oa_lines = [], []
    counts = collections.Counter()
    for doi in sorted(doi_key, key=lambda d: doi_key[d]):
        key = doi_key[doi]
        c = cr.get(doi)
        if c and c["category"] == "verified":
            raw = repo_path(root, c["response_file"])
            obj, err = ec.load_json_file(raw)
            if err is not None or ec.canonical_response_doi(obj["message"].get("DOI")) != doi:
                raise VerifyError("crossref raw file for %s does not verify" % doi)
            cr_lines.append(dumps({"dblp_key": key, "doi": doi, "source": "crossref", "retrieved": c["retrieved"],
                                   "raw_file": c["response_file"], "raw_sha256": ec.sha256_file(raw),
                                   "record": trim_crossref(obj["message"])}))
            counts["crossref_verified"] += 1
        else:
            counts["crossref_not_verified"] += 1
        o = oa.get(doi)
        if o and o["category"] == "verified":
            rel = o["response_file"]
            raw = repo_path(root, rel)
            ws = openalex_works(raw, doi)
            if not ws:
                raise VerifyError("openalex raw chunk for %s has no matching work" % doi)
            for w in sorted(ws, key=lambda x: x.get("id", "")):
                oa_lines.append(dumps({"dblp_key": key, "doi": doi, "source": "openalex", "retrieved": o["retrieved"],
                                       "raw_file": rel, "raw_sha256": ec.sha256_file(raw),
                                       "record": trim_openalex(w)}))
            counts["openalex_verified"] += 1
        else:
            counts["openalex_not_verified"] += 1
    sample_dois = set(doi_key)
    link_rows = []
    with open(args.links, "r", encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if parts[header.index("doi")] in sample_dois:
                link_rows.append(parts)
    counts["orcid_links"] = len(link_rows)
    counts["orcid_distinct"] = len(set(r[0] for r in link_rows))

    outs = {
        "crossref-sample.jsonl": cr_lines,
        "openalex-sample.jsonl": oa_lines,
    }
    for name, lines in outs.items():
        ec.atomic_write_bytes(os.path.join(args.out_dir, name), ("".join(l + "\n" for l in lines)).encode("utf-8"))
    ec.write_tsv(os.path.join(args.out_dir, "orcid-candidate-links-sample.tsv"), header, link_rows)

    run_meta = {"script_name": SCRIPT_NAME, "script_version": SCRIPT_VERSION,
                "code_version": ec.code_version(), "generated_at_utc": ec.utc_now()}
    inputs = [ec.file_ref(p) for p in (sample_xml, args.crossref_status, args.openalex_status, args.links)]
    rule = ("DBLP works = every record of %s (the 50 records of selection-10k with the smallest "
            "sha256(seed|key), seed scholarchain-dblp-2026-11-16); for each, the DOI-verified Crossref response "
            "and DOI-verified OpenAlex work(s) from the 2026-11-22 retrieval; lines sorted by dblp_key"
            % ec.display_path(sample_xml))
    meta = {
        "crossref-sample.jsonl": ("crossref", "real Crossref records (trimmed) for the DBLP sample works", CROSSREF_TRIM_RULE,
                                  {"lines": len(cr_lines)}),
        "openalex-sample.jsonl": ("openalex", "real OpenAlex works (trimmed) for the DBLP sample works", OPENALEX_TRIM_RULE,
                                  {"lines": len(oa_lines)}),
        "orcid-candidate-links-sample.tsv": ("orcid", "ORCID candidate links (from enrich_reconcile.py) restricted to the "
                                             "DBLP sample DOIs; surname heuristic is QA only", "rows copied verbatim",
                                             {"rows": len(link_rows), "distinct_orcids": counts["orcid_distinct"]}),
    }
    shas = {}
    for name, (src, desc, trim, cnt) in meta.items():
        shas[name] = ec.sidecar(os.path.join(args.out_dir, name), inputs, {
            "description": desc, "selection_rule": rule, "trim_rule": trim, "counts": cnt,
            "license": LICENSE_NOTE[src], "synthetic": False,
            "development_run_note": "derived from the 10K DEVELOPMENT selection",
        }, run_meta, src)
    print("sample counts: %s" % json.dumps(dict(sorted(counts.items()))))
    for n, s in sorted(shas.items()):
        print("%s sha256=%s" % (ec.display_path(os.path.join(args.out_dir, n)), s))
    res = verify(args, root)
    return 0 if res["status"] in ("PASS", "PASS-WITH-SKIPS") else 4


def verify(args, root):
    checked = skipped = 0
    for name in SAMPLE_NAMES:
        p = os.path.join(args.out_dir, name)
        if not os.path.isfile(p):
            raise VerifyError("missing sample %s" % p)
        try:
            ds.check_sidecar(p)
        except ds.ValidationError as e:
            raise VerifyError(str(e))
    for name, trim in (("crossref-sample.jsonl", "crossref"), ("openalex-sample.jsonl", "openalex")):
        with open(os.path.join(args.out_dir, name), "r", encoding="utf-8") as f:
            for ln, line in enumerate(f, 1):
                rec = json.loads(line)
                raw = repo_path(root, rec["raw_file"])
                if not os.path.isfile(raw):
                    skipped += 1
                    continue
                if ec.sha256_file(raw) != rec["raw_sha256"]:
                    raise VerifyError("%s:%d raw sha256 mismatch for %s" % (name, ln, rec["raw_file"]))
                if trim == "crossref":
                    obj, _ = ec.load_json_file(raw)
                    again = trim_crossref(obj["message"])
                    if ec.canonical_response_doi(obj["message"].get("DOI")) != rec["doi"]:
                        raise VerifyError("%s:%d DOI mismatch" % (name, ln))
                else:
                    ws = [w for w in openalex_works(raw, rec["doi"]) if w.get("id") == rec["record"].get("id")]
                    if len(ws) != 1:
                        raise VerifyError("%s:%d work %s not found in raw chunk" % (name, ln, rec["record"].get("id")))
                    again = trim_openalex(ws[0])
                if dumps(again) != dumps(rec["record"]):
                    raise VerifyError("%s:%d trimmed record differs from raw" % (name, ln))
                checked += 1
    status = "PASS" if skipped == 0 else ("SKIPPED-RAW-ABSENT" if checked == 0 else "PASS-WITH-SKIPS")
    print("verify: status=%s lines_checked_against_raw=%d lines_skipped_raw_absent=%d" % (status, checked, skipped))
    return {"status": status, "checked": checked, "skipped": skipped}


def run(argv):
    ap = argparse.ArgumentParser(prog=SCRIPT_NAME, description=__doc__.split("\n")[0])
    ap.add_argument("mode", choices=("build", "verify"))
    ap.add_argument("--dblp-sample", default="datasets/samples/dblp/dblp-sample-50.xml")
    ap.add_argument("--crossref-status", default="datasets/derived/enrichment/crossref-doi-status.tsv")
    ap.add_argument("--openalex-status", default="datasets/derived/enrichment/openalex-doi-status.tsv")
    ap.add_argument("--links", default="datasets/derived/enrichment/orcid-candidate-links.tsv")
    ap.add_argument("--out-dir", default="datasets/samples/enrichment")
    ap.add_argument("--repo-root", default=None, help="root that raw_file paths are relative to (default: git root or cwd)")
    ap.add_argument("--force", action="store_true", help="build: overwrite existing sample files")
    args = ap.parse_args(argv)
    root = args.repo_root or ds.repo_root() or os.getcwd()
    ec.set_display_root(root)
    if args.mode == "build":
        return build(args, root)
    res = verify(args, root)
    return 0 if res["status"] != "FAIL" else 4


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
