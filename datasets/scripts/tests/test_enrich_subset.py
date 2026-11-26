# -*- coding: utf-8 -*-
"""Offline tests for enrich_subset.py (per-selection J02 inputs)."""
import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import enrich_subset as es  # noqa: E402


class EnrichSubsetTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root)
        os.makedirs(os.path.join(self.root, "raw", "cr"))
        os.makedirs(os.path.join(self.root, "raw", "oa"))
        os.makedirs(os.path.join(self.root, "raw", "orcid", "retrieved=2026-11-26"))
        self.w("sel.txt", "10.1/A\n\n10.2/b\n")
        self.w("raw/cr/a.json", json.dumps({"status": "ok", "message": {"DOI": "10.1/a"}}))
        self.w("raw/cr/z.json", json.dumps({"status": "ok", "message": {"DOI": "10.9/z"}}))
        self.w("cr.tsv", "doi\tcategory\tresponse_file\n"
                         "10.1/a\tverified\traw/cr/a.json\n"
                         "10.9/z\tverified\traw/cr/z.json\n"
                         "10.2/b\tnot_found\t\n")
        self.w("raw/oa/c1.json", json.dumps({"results": [
            {"id": "https://openalex.org/W1", "doi": "https://doi.org/10.1/a"},
            {"id": "https://openalex.org/W9", "doi": "https://doi.org/10.9/z"},
            {"id": "https://openalex.org/W2", "doi": "https://doi.org/10.2/b"}]}))
        self.w("oa.tsv", "doi\tcategory\twork_ids\tresponse_file\n"
                         "10.1/a\tverified\tW1\traw/oa/c1.json\n"
                         "10.2/b\tverified\tW2;W1\traw/oa/c1.json\n"
                         "10.9/z\tverified\tW9\traw/oa/c1.json\n")
        self.w("raw/orcid/retrieved=2026-11-26/0000-0002-1825-0097.json",
               json.dumps({"orcid-identifier": {"path": "0000-0002-1825-0097"}}))
        self.w("raw/orcid/retrieved=2026-11-26/broken.json", "{not json")

    def w(self, rel, text):
        with open(os.path.join(self.root, rel), "w", encoding="utf-8") as f:
            f.write(text)

    def run_subset(self):
        rc = es.main(["--selection", "10k", "--repo-root", self.root, "--selection-dois", "sel.txt",
                      "--crossref-status", "cr.tsv", "--openalex-status", "oa.tsv",
                      "--orcid-glob", "raw/orcid/retrieved=*/*", "--out-dir", "out"])
        self.assertEqual(rc, 0)
        out = os.path.join(self.root, "out")
        read = lambda n: [json.loads(l) for l in open(os.path.join(out, n), encoding="utf-8")]
        return read("crossref.jsonl"), read("openalex.jsonl"), read("orcid.jsonl"), out

    def test_only_verified_selection_dois_are_kept(self):
        cr, oa, orc, _ = self.run_subset()
        self.assertEqual([r["message"]["DOI"] for r in cr], ["10.1/a"])
        self.assertEqual(sorted(w["id"].rsplit("/", 1)[-1] for w in oa), ["W1", "W2"])
        self.assertEqual(len(orc), 1)  # the unparseable ORCID file is skipped

    def test_each_openalex_work_written_once(self):
        _, oa, _, _ = self.run_subset()
        ids = [w["id"] for w in oa]
        self.assertEqual(len(ids), len(set(ids)))

    def test_rerun_is_byte_identical_and_manifest_counts(self):
        _, _, _, out = self.run_subset()
        first = open(os.path.join(out, "openalex.jsonl"), "rb").read()
        self.run_subset()
        self.assertEqual(first, open(os.path.join(out, "openalex.jsonl"), "rb").read())
        m = json.load(open(os.path.join(out, "SUBSET-MANIFEST.json"), encoding="utf-8"))
        self.assertEqual(m["outputs"]["crossref.jsonl"]["lines"], 1)
        self.assertEqual(m["inputs"]["selection_dois"]["distinct_dois"], 2)

    def test_missing_input_exits_3(self):
        rc = es.main(["--selection", "10k", "--repo-root", self.root, "--selection-dois", "nope.txt"])
        self.assertEqual(rc, 3)


if __name__ == "__main__":
    unittest.main()
