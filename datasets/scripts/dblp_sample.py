#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""dblp_sample.py -- write a small, real, deterministic DBLP sample from a selection.

Owner: dataset-engineer (datasets/**)

Takes the --n records with the smallest rank sha256(seed + "|" + key) from
selection-<label>.tsv (seed read from the selection's provenance sidecar),
copies their ORIGINAL bytes out of selection-<label>.xml (no re-serialisation;
dump order preserved), wraps them with the XML declaration and DOCTYPE, places
a copy of dblp.dtd next to the sample, writes provenance sidecars and
validates the result. No network access. Refuses to overwrite without --force.
"""

from __future__ import print_function

import argparse
import json
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dblp_select as ds  # noqa: E402
import dblp_validate as dv  # noqa: E402

SCRIPT_NAME = "dblp_sample.py"
SCRIPT_VERSION = "1.0.0"


def run(argv):
    ap = argparse.ArgumentParser(prog=SCRIPT_NAME, description=__doc__.split("\n")[0])
    ap.add_argument("--selection-dir", required=True, help="directory with selection-<label>.* outputs")
    ap.add_argument("--label", default="10k", help="selection label to sample from (default %(default)s)")
    ap.add_argument("--n", type=int, default=50, help="number of records (default %(default)s)")
    ap.add_argument("--out", required=True, help="output XML path, e.g. datasets/samples/dblp/dblp-sample-50.xml")
    ap.add_argument("--force", action="store_true", help="overwrite existing outputs")
    args = ap.parse_args(argv)

    sel_xml = os.path.join(args.selection_dir, "selection-%s.xml" % args.label)
    sel_tsv = os.path.join(args.selection_dir, "selection-%s.tsv" % args.label)
    out_dir = os.path.dirname(os.path.abspath(args.out))
    dtd_out = os.path.join(out_dir, ds.RAW_DTD_NAME)
    targets = [args.out, args.out + ".provenance.json", dtd_out, dtd_out + ".provenance.json"]
    existing = [p for p in targets if os.path.exists(p)]
    if existing and not args.force:
        raise ds.UsageError("refusing to overwrite %s; use --force" % existing[0])

    xml_sha = ds.check_sidecar(sel_xml)
    tsv_sha = ds.check_sidecar(sel_tsv)
    with open(sel_xml + ".provenance.json", "r", encoding="utf-8") as f:
        sel_meta = json.load(f)
    seed = sel_meta["seed"]
    tsv = ds.read_tsv(sel_tsv)
    if args.n <= 0 or args.n > len(tsv):
        raise ds.UsageError("--n must be between 1 and %d" % len(tsv))
    chosen = set(sorted(tsv, key=lambda k: (ds.rank_of(seed, k), k))[: args.n])

    _, _, raws = ds.parse_extract(sel_xml)
    parts = [ds.XML_HEADER]
    for key, raw in raws.items():  # extract (= dump) order
        if key in chosen:
            parts.append(raw)
            parts.append(b"\n")
    parts.append(ds.XML_FOOTER)
    if not os.path.isdir(out_dir):
        os.makedirs(out_dir)
    ds.atomic_write_bytes(args.out, b"".join(parts))
    shutil.copyfile(os.path.join(args.selection_dir, ds.RAW_DTD_NAME), dtd_out)

    run_meta = {
        "script_name": SCRIPT_NAME,
        "script_version": SCRIPT_VERSION,
        "code_version": ds.code_version(),
        "generated_at_utc": ds.utc_now(),
    }
    inputs = [
        {"path": ds.display_path(sel_xml), "size_bytes": os.path.getsize(sel_xml), "sha256": xml_sha},
        {"path": ds.display_path(sel_tsv), "size_bytes": os.path.getsize(sel_tsv), "sha256": tsv_sha},
    ] + sel_meta.get("inputs", [])
    ds.sidecar(dtd_out, sel_meta.get("inputs", [])[1:2], seed,
               {"description": "verbatim copy of dblp.dtd (CC0 1.0 header) so the sample parses offline"}, run_meta)
    sha = ds.sidecar(args.out, inputs, seed, {
        "description": "real DBLP records (original bytes, ISO-8859-1): the %d records of selection-%s with the "
                       "smallest sha256(seed|key), in dump order" % (args.n, args.label),
        "sample_rule": "n smallest sha256(seed + '|' + key) among selection-%s keys" % args.label,
        "counts": {"records": args.n},
        "license": "CC0 1.0 (dblp metadata)",
    }, run_meta)
    n = dv.validate_sample(args.out, sel_tsv, args.n)
    print("sample=%s records=%d sha256=%s" % (ds.display_path(args.out), n, sha))
    return 0


def main(argv=None):
    try:
        return run(sys.argv[1:] if argv is None else argv)
    except ds.SelectError as e:
        sys.stderr.write("ERROR: %s\n" % e)
        return e.exit_code


if __name__ == "__main__":
    sys.exit(main())
