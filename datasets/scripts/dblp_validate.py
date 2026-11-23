#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""dblp_validate.py -- re-validate DBLP selection extracts and the committed sample.

Owner: dataset-engineer (datasets/**)

Checks, with no network access:
  * every selection-<label>.xml under --out-dir fully parses with entities
    resolved from the dblp.dtd copy next to it;
  * record count, keys, types, years, venues, DOIs, author counts and titles
    agree with selection-<label>.tsv; the DOI list is sorted, unique and equal
    to the TSV DOIs; output sha256 values match their provenance sidecars;
  * smaller selections are subsets of larger ones;
  * optionally (--sample), the sample XML parses against the DTD next to it,
    every sample record matches the corresponding row of the --sample-of TSV,
    and its sha256 matches its sidecar.

Exit 0 when everything passes, 4 on validation failure, 1 on other errors.
"""

from __future__ import print_function

import argparse
import glob
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dblp_select as ds  # noqa: E402


def validate_sample(sample_path, tsv_path, expected_n=None):
    rows, count, _ = ds.parse_extract(sample_path)
    tsv = ds.read_tsv(tsv_path)
    missing = sorted(set(rows) - set(tsv))
    if missing:
        raise ds.ValidationError("sample keys not in %s: %r" % (tsv_path, missing[:3]))
    for k, row in rows.items():
        if row != tsv[k]:
            raise ds.ValidationError("sample field mismatch for %s" % k)
    if expected_n is not None and count != expected_n:
        raise ds.ValidationError("sample has %d records, expected %d" % (count, expected_n))
    ds.check_sidecar(sample_path)
    return count


def main(argv=None):
    ap = argparse.ArgumentParser(prog="dblp_validate.py", description=__doc__.split("\n")[0])
    ap.add_argument("--out-dir", required=True, help="directory with selection-<label>.* outputs")
    ap.add_argument("--sample", help="optional sample XML to validate (e.g. datasets/samples/dblp/dblp-sample-50.xml)")
    ap.add_argument("--sample-of", default="10k", help="selection label the sample was drawn from (default %(default)s)")
    ap.add_argument("--sample-n", type=int, default=None, help="expected number of sample records")
    args = ap.parse_args(sys.argv[1:] if argv is None else argv)
    try:
        labels = sorted(
            re.match(r"selection-(.+)\.tsv$", os.path.basename(p)).group(1)
            for p in glob.glob(os.path.join(args.out_dir, "selection-*.tsv"))
        )
        if not labels:
            raise ds.ValidationError("no selection-*.tsv found in %s" % args.out_dir)
        res = ds.validate_out_dir(args.out_dir, labels)
        for r in res["selections"]:
            print("OK selection-%s: records=%d distinct_dois=%d keys/years/titles/authors/venues/dois match TSV; %s"
                  % (r["label"], r["records"], r["distinct_dois"], r["xml_parse"]))
        print("OK subset: %s" % json.dumps(res["subset"], sort_keys=True))
        if args.sample:
            tsv = os.path.join(args.out_dir, "selection-%s.tsv" % args.sample_of)
            n = validate_sample(args.sample, tsv, args.sample_n)
            print("OK sample %s: records=%d all present in selection-%s with matching fields; sidecar sha256 ok"
                  % (ds.display_path(args.sample), n, args.sample_of))
        return 0
    except ds.SelectError as e:
        sys.stderr.write("VALIDATION FAILED: %s\n" % e)
        return e.exit_code


if __name__ == "__main__":
    sys.exit(main())
