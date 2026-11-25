#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""orcid_select.py -- deterministic, seeded selection of the bounded ORCID fetch subset.

Owner: dataset-engineer (datasets/**)

No network access. Standard library only (Python >= 3.8).

Reads datasets/derived/enrichment/orcid-candidates.tsv (written by
enrich_reconcile.py) and writes the iDs that enrich_fetch.py orcid will fetch:

  eligible = checksum_valid is true AND n_dblp_works >= --min-dblp-works (default 2),
             i.e. the iD is attached to MORE THAN ONE DBLP work of the 50K selection
             (more than one author mention), so entity resolution has repeated
             mentions to link. iDs seen on a single work are used only to fill
             the bound when fewer than --n eligible iDs exist.
  order    = ascending sha256(seed + "|" + orcid); the first --n are taken.
             Not "first N" of the candidate file: the choice does not depend on file order.

The output file holds one normalised iD per line in rank order (so the first
line is the best single-record connectivity test) with a .provenance.json
sidecar recording rule, seed, pool sizes, input sha256 and output sha256.
This script does not decide entity matches; it only picks which public
records to retrieve.

Exit codes: 0 ok; 2 usage; 3 missing input; 1 other error.
"""

from __future__ import print_function

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dblp_select as ds  # noqa: E402
import enrich_common as ec  # noqa: E402

SCRIPT_NAME = "orcid_select.py"
SCRIPT_VERSION = "1.0.0"
DEFAULT_SEED = "scholarchain-orcid-2026-11-24"


class UsageError(ec.EnrichError):
    exit_code = 2


class MissingInput(ec.EnrichError):
    exit_code = 3


def read_candidates(path):
    if not os.path.isfile(path):
        raise MissingInput("missing input %s (run enrich_reconcile.py first)" % path)
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        for col in ("orcid", "checksum_valid", "n_dblp_works"):
            if col not in header:
                raise UsageError("%s lacks column %s" % (path, col))
        for line in f:
            d = dict(zip(header, line.rstrip("\n").split("\t")))
            rows.append((d["orcid"], d["checksum_valid"] == "true", int(d["n_dblp_works"])))
    return rows


def select(rows, seed, n, min_works):
    """Return (selected ids in rank order, pool stats). Pure function of (rows, seed, n, min_works)."""
    ranked = []
    seen = set()
    for oid, valid, works in rows:
        norm, ok = ec.normalize_orcid(oid)
        if not ok or not valid or norm in seen:
            continue
        seen.add(norm)
        ranked.append((works >= min_works, ec.sha256_text(seed + "|" + norm), norm))
    preferred = sorted(r[1:] for r in ranked if r[0])
    rest = sorted(r[1:] for r in ranked if not r[0])
    chosen = [x[1] for x in preferred[:n]]
    filled = 0
    if len(chosen) < n:
        extra = [x[1] for x in rest[:n - len(chosen)]]
        filled = len(extra)
        chosen += extra
    stats = {"candidates_valid": len(ranked), "eligible_pool": len(preferred), "selected": len(chosen),
             "selected_from_eligible": len(chosen) - filled, "filled_from_single_work_ids": filled}
    return chosen, stats


def run(argv):
    ap = argparse.ArgumentParser(prog=SCRIPT_NAME, description=__doc__.split("\n")[0])
    ap.add_argument("--candidates", default="datasets/derived/enrichment/orcid-candidates.tsv")
    ap.add_argument("--out", default="datasets/derived/enrichment/orcid-fetch-2000.txt")
    ap.add_argument("--n", type=int, default=2000)
    ap.add_argument("--seed", default=DEFAULT_SEED)
    ap.add_argument("--min-dblp-works", type=int, default=2)
    ap.add_argument("--root", default=None, help="display root for sidecar paths (default: git root)")
    args = ap.parse_args(argv)
    if args.n <= 0 or args.min_dblp_works < 1:
        raise UsageError("--n and --min-dblp-works must be positive")
    ec.set_display_root(args.root or ds.repo_root() or os.getcwd())
    rows = read_candidates(args.candidates)
    chosen, stats = select(rows, args.seed, args.n, args.min_dblp_works)
    out_dir = os.path.dirname(args.out)
    if out_dir and not os.path.isdir(out_dir):
        os.makedirs(out_dir)
    ec.atomic_write_bytes(args.out, "".join(c + "\n" for c in chosen).encode("utf-8"))
    run_meta = {"script_name": SCRIPT_NAME, "script_version": SCRIPT_VERSION,
                "code_version": ec.code_version(), "generated_at_utc": ec.utc_now()}
    sha = ec.sidecar(args.out, [ec.file_ref(args.candidates)], {
        "description": "bounded ORCID fetch list (input for enrich_fetch.py orcid), rank order",
        "rule": ("eligible = checksum-valid iD attached to >= %d distinct DBLP works of the 50K selection "
                 "(more than one author mention); ranked by ascending sha256(seed|orcid); first %d taken; "
                 "iDs on a single work are used only if the eligible pool is smaller than the bound"
                 % (args.min_dblp_works, args.n)),
        "seed": args.seed, "min_dblp_works": args.min_dblp_works, "bound": args.n,
        "counts": stats, "lines": len(chosen),
        "human_decision": "2026-11-24: bounded 2,000-record ORCID fetch from the free public API, no API key",
        "license": "list of public ORCID iDs already present in Crossref/OpenAlex records; ORCID public data terms apply to fetched records",
    }, run_meta, "orcid")
    print("orcid_select: %s sha256=%s" % (ec.display_path(args.out), sha))
    print("orcid_select: %s" % ", ".join("%s=%s" % kv for kv in sorted(stats.items())))
    return 0


def main(argv=None):
    try:
        return run(sys.argv[1:] if argv is None else argv)
    except ec.EnrichError as e:
        sys.stderr.write("ERROR: %s\n" % e)
        return e.exit_code


if __name__ == "__main__":
    sys.exit(main())
