#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""enrich_subset.py -- per-selection JSON Lines inputs for the Hadoop J02 normalizers.

Owner: dataset-engineer (datasets/**)

No network access. Standard library only (Python >= 3.8). Deterministic: output
depends only on the input files (the timestamp appears only in the sidecar).

Builds the bounded enrichment inputs of one DBLP selection (10k or 50k) so the
cloud pipeline (scripts/run-bounded-pipeline.sh) can run the Crossref, OpenAlex
and ORCID normalization jobs (J02c, J02a, J02b) with
`-D scholarchain.input.layout=json-lines`:

  <out-dir>/crossref.jsonl  one raw Crossref API response per line, for every
                            DOI of the selection whose Crossref category is
                            "verified" (crossref-doi-status.tsv)
  <out-dir>/openalex.jsonl  one raw OpenAlex work object per line, for every
                            DOI of the selection whose OpenAlex category is
                            "verified" (openalex-doi-status.tsv, work_ids
                            column); each work ID is written once
  <out-dir>/orcid.jsonl     one raw ORCID record per line, for every parseable
                            record under datasets/raw/orcid/retrieved=*/
                            (the 2,000-iD bounded list); ORCID records are
                            researcher profiles, so they are not filtered by DOI
  <out-dir>/SUBSET-MANIFEST.json  rule, inputs, counts and sha256 of each output

Why: the enrichment sources were fetched for the DOIs of the 50k selection.
Feeding every fetched record into a 10k run adds works that have no DBLP
counterpart and makes blocking/feature construction explode (an earlier full
local attempt projected ~48M candidate pairs). Restricting each source to the
selection's DOIs keeps the run bounded and keeps every enrichment work linked
to a DBLP work by an exact DOI.

Lines are written in sorted order (by DOI, then source record ID) with compact
JSON, so reruns are byte-identical.

Exit codes: 0 ok; 2 usage; 3 missing input; 1 other.
"""

from __future__ import print_function

import argparse
import glob
import hashlib
import json
import os
import sys
import time

DEFAULT_ENRICH = os.path.join("datasets", "derived", "enrichment")
DEFAULT_DBLP = os.path.join("datasets", "derived", "dblp")


def norm_doi(doi):
    d = (doi or "").strip().lower()
    for p in ("https://doi.org/", "http://doi.org/", "https://dx.doi.org/", "http://dx.doi.org/", "doi:"):
        if d.startswith(p):
            d = d[len(p):]
            break
    return d.strip()


def read_tsv(path):
    with open(path, encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        for line in f:
            if not line.strip():
                continue
            cells = line.rstrip("\n").split("\t")
            cells += [""] * (len(header) - len(cells))
            yield dict(zip(header, cells))


def compact(obj):
    return json.dumps(obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def resolve(root, path):
    return path if os.path.isabs(path) else os.path.join(root, path)


def selection_dois(path):
    with open(path, encoding="utf-8") as f:
        return {norm_doi(line) for line in f if norm_doi(line)}


def crossref_lines(root, status_path, wanted):
    out = []
    for row in read_tsv(status_path):
        doi = norm_doi(row.get("doi"))
        if doi not in wanted or row.get("category") != "verified" or not row.get("response_file"):
            continue
        with open(resolve(root, row["response_file"]), encoding="utf-8") as f:
            obj = json.load(f)
        out.append((doi, row["response_file"], compact(obj)))
    out.sort()
    return [line for _, _, line in out]


def openalex_lines(root, status_path, wanted):
    want_ids = {}  # chunk file -> set of work ids
    for row in read_tsv(status_path):
        doi = norm_doi(row.get("doi"))
        if doi not in wanted or row.get("category") != "verified" or not row.get("response_file"):
            continue
        ids = [w.strip() for w in row.get("work_ids", "").replace(",", ";").split(";") if w.strip()]
        want_ids.setdefault(row["response_file"], set()).update(ids)
    seen = {}
    for chunk in sorted(want_ids):
        with open(resolve(root, chunk), encoding="utf-8") as f:
            obj = json.load(f)
        for work in obj.get("results", []):
            wid = str(work.get("id", "")).rsplit("/", 1)[-1]
            if wid in want_ids[chunk] and wid not in seen:
                seen[wid] = (norm_doi(work.get("doi")), wid, compact(work))
    return [line for _, _, line in sorted(seen.values())]


def orcid_lines(root, orcid_glob):
    out = []
    for path in sorted(glob.glob(resolve(root, orcid_glob))):
        if not os.path.isfile(path):
            continue
        try:
            with open(path, encoding="utf-8") as f:
                obj = json.load(f)
        except (ValueError, UnicodeDecodeError):
            continue  # an unparseable record is skipped and counted below
        oid = ((obj.get("orcid-identifier") or {}).get("path")) or os.path.basename(path)
        out.append((oid, compact(obj)))
    out.sort()
    return [line for _, line in out]


def write_lines(path, lines):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        for line in lines:
            f.write(line + "\n")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--selection", required=True, choices=["10k", "50k"])
    ap.add_argument("--repo-root", default=".")
    ap.add_argument("--selection-dois", default=None,
                    help="default datasets/derived/dblp/selection-<sel>.dois.txt")
    ap.add_argument("--crossref-status", default=os.path.join(DEFAULT_ENRICH, "crossref-doi-status.tsv"))
    ap.add_argument("--openalex-status", default=os.path.join(DEFAULT_ENRICH, "openalex-doi-status.tsv"))
    ap.add_argument("--orcid-glob", default=os.path.join("datasets", "raw", "orcid", "retrieved=*", "*"))
    ap.add_argument("--out-dir", default=None, help="default datasets/derived/enrichment/subset-<sel>")
    args = ap.parse_args(argv)

    root = args.repo_root
    sel_path = args.selection_dois or os.path.join(DEFAULT_DBLP, "selection-%s.dois.txt" % args.selection)
    out_dir = args.out_dir or os.path.join(DEFAULT_ENRICH, "subset-%s" % args.selection)
    for p in (sel_path, args.crossref_status, args.openalex_status):
        if not os.path.isfile(resolve(root, p)):
            print("enrich_subset: missing input %s" % p, file=sys.stderr)
            return 3

    wanted = selection_dois(resolve(root, sel_path))
    cr = crossref_lines(root, resolve(root, args.crossref_status), wanted)
    oa = openalex_lines(root, resolve(root, args.openalex_status), wanted)
    oc = orcid_lines(root, args.orcid_glob)

    os.makedirs(resolve(root, out_dir), exist_ok=True)
    outputs = {}
    for name, lines in (("crossref.jsonl", cr), ("openalex.jsonl", oa), ("orcid.jsonl", oc)):
        p = resolve(root, os.path.join(out_dir, name))
        write_lines(p, lines)
        outputs[name] = {"lines": len(lines), "sha256": sha256_file(p)}

    manifest = {
        "producer": "dataset-engineer/enrich_subset.py",
        "selection": args.selection,
        "rule": ("Crossref and OpenAlex: records whose normalized DOI is in the selection's DOI list and whose "
                 "reconciled category is 'verified'. ORCID: every parseable fetched record (profiles, not works)."),
        "inputs": {
            "selection_dois": {"path": sel_path, "distinct_dois": len(wanted), "sha256": sha256_file(resolve(root, sel_path))},
            "crossref_status": {"path": args.crossref_status, "sha256": sha256_file(resolve(root, args.crossref_status))},
            "openalex_status": {"path": args.openalex_status, "sha256": sha256_file(resolve(root, args.openalex_status))},
            "orcid_glob": args.orcid_glob,
        },
        "outputs": outputs,
        "generated_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    with open(resolve(root, os.path.join(out_dir, "SUBSET-MANIFEST.json")), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
        f.write("\n")
    print(json.dumps({"selection": args.selection, "out_dir": out_dir,
                      "crossref": len(cr), "openalex": len(oa), "orcid": len(oc)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
