# -*- coding: utf-8 -*-
"""Self-tests for datasets/scripts/dblp_select.py, dblp_validate.py, dblp_sample.py.

Owner: dataset-engineer. Synthetic fixtures only (tests/fixtures/*-synthetic.*);
no network, no real DBLP data. Run with:
    python3 -I -B -m unittest discover -s datasets/scripts/tests -v
"""

import contextlib
import gzip
import hashlib
import io
import os
import random
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.dirname(HERE)
FIXTURES = os.path.join(HERE, "fixtures")
if SCRIPTS not in sys.path:
    sys.path.insert(0, SCRIPTS)

import dblp_sample  # noqa: E402
import dblp_select as ds  # noqa: E402
import dblp_validate  # noqa: E402

FIXTURE_XML = os.path.join(FIXTURES, "dblp-synthetic.xml")
FIXTURE_DTD = os.path.join(FIXTURES, "dblp-synthetic.dtd")


def read(path):
    with open(path, "rb") as f:
        return f.read()


def make_raw_dir(root, xml_bytes, dtd_path=FIXTURE_DTD):
    raw = os.path.join(root, "raw")
    os.makedirs(raw)
    with open(os.path.join(raw, "dblp.xml.gz"), "wb") as f:
        f.write(gzip.compress(xml_bytes, mtime=0))
    shutil.copyfile(dtd_path, os.path.join(raw, "dblp.dtd"))
    with open(os.path.join(raw, "SHA256SUMS"), "w") as f:
        for name in ("dblp.xml.gz", "dblp.dtd"):
            f.write("%s  %s\n" % (ds.sha256_file(os.path.join(raw, name)), name))
    return raw


def quiet_main(fn, argv):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = fn(argv)
    return code, out.getvalue(), err.getvalue()


def synthetic_records(n_per_year, seed=7):
    """Generate eligible synthetic records: {year: count}. Returns list of bytes."""
    rng = random.Random(seed)
    recs = []
    i = 0
    for year in sorted(n_per_year):
        for _ in range(n_per_year[year]):
            i += 1
            venue = "<journal>J. Syn %d</journal>" % rng.randint(1, 5)
            tag = "article"
            if i % 3 == 0:
                tag = "inproceedings"
                venue = "<booktitle>CONF%d</booktitle>" % rng.randint(1, 4)
            recs.append((
                '<%s mdate="2026-01-01" key="syn/r%05d">\n<author>A %d</author>\n<author>B %d</author>\n'
                "<title>Synthetic &eacute;tude %d.</title>\n<year>%d</year>\n%s\n"
                "<ee>https://doi.org/10.9999/SYN.%d</ee>\n</%s>" % (tag, i, i, i, i, year, venue, i, tag)
            ).encode("latin-1"))
    return recs


def wrap(recs):
    return ds.XML_HEADER + b"\n".join(recs) + b"\n" + ds.XML_FOOTER


class Sha256Test(unittest.TestCase):
    def test_known_answers(self):
        self.assertEqual(ds.sha256(b"abc").hexdigest(),
                         "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        self.assertEqual(ds.sha256(b"").hexdigest(),
                         "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        self.assertEqual(len(ds.rank_of("s", "k")), 64)


class CanonicalDoiTest(unittest.TestCase):
    def test_url_forms(self):
        cases = {
            "https://doi.org/10.1145/3299874.3318221": "10.1145/3299874.3318221",
            "http://doi.org/10.1145/ABC": "10.1145/abc",
            "https://dx.doi.org/10.1007/X": "10.1007/x",
            "http://dx.doi.org/10.1007/X": "10.1007/x",
            "https://www.doi.org/10.1/A": "10.1/a",
            "HTTPS://DOI.ORG/10.1109/TSE.2020.1": "10.1109/tse.2020.1",
            "https://doi.org/10.1000/a%2Fb%3Cc%3E": "10.1000/a/b<c>",
            "  https://doi.org/%2010.5/x%20  ": "10.5/x",
        }
        for ee, want in cases.items():
            self.assertEqual(ds.canonical_doi(ee), (True, want), ee)

    def test_rejections(self):
        self.assertEqual(ds.canonical_doi("https://doi.org/abc"), (True, None))
        self.assertEqual(ds.canonical_doi("https://doi.org/11.1/x"), (True, None))
        self.assertEqual(ds.canonical_doi("https://doi.org/10.1/a%20b"), (True, None))
        self.assertEqual(ds.canonical_doi("https://arxiv.org/abs/1"), (False, None))
        self.assertEqual(ds.canonical_doi("https://notdoi.org/10.1/x"), (False, None))
        self.assertEqual(ds.canonical_doi("doi:10.1/x"), (False, None))


class ParserTest(unittest.TestCase):
    def parse(self, data, dtd=FIXTURE_DTD):
        recs = []

        def cb(stream, rec):
            recs.append((rec, stream.raw(rec)))

        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp)
        shutil.copyfile(dtd, os.path.join(tmp, "dblp.dtd"))
        s = ds.DblpRecordStream(os.path.join(tmp, "dblp.dtd"), cb)
        s.parse_file(io.BytesIO(data))
        return s, recs

    def test_entities_resolved_from_local_dtd_and_latin1(self):
        s, recs = self.parse(read(FIXTURE_XML))
        first, raw = recs[0]
        self.assertEqual(first.fields["title"], [u"Köln graphs café."])
        self.assertEqual(first.fields["author"], [u"Anna König", u"Bo Café"])
        # original bytes preserved verbatim: entity reference and raw ISO-8859-1 byte
        self.assertIn(b"K&ouml;ln <i>graphs</i> caf\xe9.", raw)
        self.assertTrue(raw.startswith(b'<article mdate="2024-01-02"'))
        self.assertTrue(raw.endswith(b"</article>"))
        self.assertEqual(sum(s.type_counts.values()), 14)
        self.assertEqual(s.type_counts["www"], 1)
        self.assertEqual(s.max_mdate, "2026-11-10")

    def test_raw_bytes_are_exact_slices(self):
        data = read(FIXTURE_XML)
        _, recs = self.parse(data)
        for rec, raw in recs:
            self.assertEqual(data[rec.start:rec.end], raw)

    def test_offsets_across_chunk_boundaries(self):
        data = read(FIXTURE_XML)
        _, ref = self.parse(data)
        old = ds.CHUNK_SIZE
        ds.CHUNK_SIZE = 7
        try:
            _, small = self.parse(data)
        finally:
            ds.CHUNK_SIZE = old
        self.assertEqual([r for _, r in ref], [r for _, r in small])

    def test_missing_dtd_fails(self):
        with self.assertRaises(ds.ChecksumError):
            ds.DblpRecordStream("/nonexistent/dblp.dtd", lambda s, r: None)

    def test_undeclared_entity_fails(self):
        data = wrap([b'<article key="k"><title>&nosuchentity;</title></article>'])
        with self.assertRaises(ds.SelectError):
            self.parse(data)


class AllocateTest(unittest.TestCase):
    def test_largest_remainder(self):
        self.assertEqual(ds.allocate({2000: 1, 2001: 1, 2002: 1}, 2), {2000: 1, 2001: 1, 2002: 0})
        self.assertEqual(ds.allocate({2000: 10, 2001: 30}, 4), {2000: 1, 2001: 3})
        self.assertEqual(ds.allocate({2000: 5, 2001: 5, 2002: 90}, 10), {2000: 1, 2001: 0, 2002: 9})

    def test_nested_caps_hold_for_random_counts(self):
        rng = random.Random(11)
        for _ in range(300):
            counts = dict((1990 + i, rng.choice([1, 1, 2, 3, 50, 400, 5000])) for i in range(rng.randint(1, 30)))
            total = sum(counts.values())
            big = rng.randint(1, total)
            small = rng.randint(1, big)
            allocs = ds.allocate_nested(counts, [small, big])
            self.assertEqual(sum(allocs[big].values()), big)
            self.assertEqual(sum(allocs[small].values()), small)
            for y in counts:
                self.assertLessEqual(allocs[small][y], allocs[big][y])
                self.assertLessEqual(allocs[big][y], counts[y])


class EndToEndTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)

    def run_select(self, raw, out, sizes, extra=()):
        return quiet_main(ds.main, ["--raw-dir", raw, "--out-dir", out, "--sizes", sizes, "--seed", "test-seed"] + list(extra))

    def test_eligibility_ordering_and_doi_dedup(self):
        raw = make_raw_dir(self.tmp, read(FIXTURE_XML))
        out = os.path.join(self.tmp, "out")
        code, stdout, err = self.run_select(raw, out, "1,3")
        self.assertEqual(code, 0, err)
        import json
        with open(os.path.join(out, "eligible-index-summary.json")) as f:
            summ = json.load(f)
        self.assertEqual(summ["eligibility_first_failing_reason_counts"], {
            "type_not_article_or_inproceedings": 2,
            "key_missing": 1,
            "key_duplicate": 2,
            "title_empty": 1,
            "year_not_numeric": 1,
            "fewer_than_2_authors": 1,
            "no_doi_org_ee": 1,
            "doi_invalid": 1,
        })
        self.assertEqual(summ["eligible"], 4)
        self.assertEqual(summ["pool_after_doi_dedup"], 3)
        self.assertEqual(summ["record_type_counts"], {"article": 11, "inproceedings": 1, "proceedings": 1, "www": 1})
        self.assertEqual(summ["doi_dedup"]["duplicate_doi_groups"], 1)
        # tie rule: smallest rank wins
        winner = min(["journals/syn/Good1", "journals/syn/Good1b"], key=lambda k: ds.rank_of("test-seed", k))
        tsv = ds.read_tsv(os.path.join(out, "selection-3.tsv"))
        self.assertIn(winner, tsv)
        self.assertEqual(len(tsv), 3)
        self.assertEqual(tsv[winner][4], "10.1000/abc/1")
        self.assertEqual(tsv["journals/syn/Good3"][4], "10.3000/xyz")
        self.assertEqual(tsv["conf/syn/Good2"][4], "10.2000/conf.2")
        if winner == "journals/syn/Good1":
            self.assertEqual(tsv[winner][6], u"Köln graphs café.")
        # extract holds original bytes and its own DTD copy
        xml = read(os.path.join(out, "selection-3.xml"))
        self.assertTrue(xml.startswith(ds.XML_HEADER))
        self.assertTrue(os.path.isfile(os.path.join(out, "dblp.dtd")))
        if winner == "journals/syn/Good1":
            self.assertIn(b"K&ouml;ln <i>graphs</i> caf\xe9.", xml)

    def generated_raw(self, name, recs):
        root = os.path.join(self.tmp, name)
        os.makedirs(root)
        with open(os.path.join(root, "dblp-generated-synthetic.xml"), "wb") as f:
            f.write(wrap(recs))
        return make_raw_dir(root, wrap(recs))

    def test_deterministic_and_input_order_independent_with_subset(self):
        recs = synthetic_records({2001: 7, 2002: 40, 2003: 120, 2004: 3, 2005: 230})
        shuffled = list(recs)
        random.Random(3).shuffle(shuffled)
        raw_a = self.generated_raw("a", recs)
        raw_b = self.generated_raw("b", shuffled)
        out_a, out_b, out_a2 = (os.path.join(self.tmp, x) for x in ("oa", "ob", "oa2"))
        for raw, out in ((raw_a, out_a), (raw_b, out_b), (raw_a, out_a2)):
            code, _, err = self.run_select(raw, out, "20,100")
            self.assertEqual(code, 0, err)
        for name in ("selection-20.tsv", "selection-100.tsv", "selection-20.dois.txt", "selection-100.dois.txt"):
            self.assertEqual(read(os.path.join(out_a, name)), read(os.path.join(out_b, name)), name)
        for name in ("selection-20.xml", "selection-100.xml"):
            # identical input -> byte-identical output (idempotent)
            self.assertEqual(read(os.path.join(out_a, name)), read(os.path.join(out_a2, name)), name)
        k20 = set(ds.read_tsv(os.path.join(out_a, "selection-20.tsv")))
        k100 = set(ds.read_tsv(os.path.join(out_a, "selection-100.tsv")))
        self.assertEqual(len(k20), 20)
        self.assertEqual(len(k100), 100)
        self.assertTrue(k20 <= k100)
        # not "first N": selection differs from the first 100 keys of the input
        self.assertNotEqual(k100, set("syn/r%05d" % i for i in range(1, 101)))
        # stratified: proportional allocation 100 * n_y / 400
        tsv100 = ds.read_tsv(os.path.join(out_a, "selection-100.tsv"))
        years = [row[2] for row in tsv100.values()]
        self.assertEqual(dict((y, years.count(y)) for y in set(years)),
                         {"2001": 2, "2002": 10, "2003": 30, "2004": 1, "2005": 57})
        # within a year the smallest ranks are taken (2003 = syn/r00048 .. syn/r00167)
        keys2003 = ["syn/r%05d" % i for i in range(48, 168)]
        want = set(sorted(keys2003, key=lambda k: ds.rank_of("test-seed", k))[:30])
        self.assertEqual(want, set(k for k, row in tsv100.items() if row[2] == "2003"))
        # validator agrees
        code, stdout, err = quiet_main(dblp_validate.main, ["--out-dir", out_a])
        self.assertEqual(code, 0, err)
        self.assertIn("OK subset", stdout)

    def test_refuses_to_overwrite_without_force(self):
        raw = make_raw_dir(self.tmp, read(FIXTURE_XML))
        out = os.path.join(self.tmp, "out")
        self.assertEqual(self.run_select(raw, out, "1,3")[0], 0)
        before = read(os.path.join(out, "selection-3.tsv"))
        code, _, err = self.run_select(raw, out, "1,3")
        self.assertEqual(code, 2)
        self.assertIn("refusing to overwrite", err)
        self.assertEqual(read(os.path.join(out, "selection-3.tsv")), before)
        code, _, err = self.run_select(raw, out, "1,3", ["--force"])
        self.assertEqual(code, 0, err)
        self.assertEqual(read(os.path.join(out, "selection-3.tsv")), before)

    def test_refuses_on_checksum_mismatch(self):
        raw = make_raw_dir(self.tmp, read(FIXTURE_XML))
        with open(os.path.join(raw, "SHA256SUMS"), "w") as f:
            f.write("%s  dblp.xml.gz\n%s  dblp.dtd\n" % ("0" * 64, ds.sha256_file(os.path.join(raw, "dblp.dtd"))))
        out = os.path.join(self.tmp, "out")
        code, _, err = self.run_select(raw, out, "1,3")
        self.assertEqual(code, 3)
        self.assertIn("checksum mismatch", err)
        self.assertFalse(os.path.exists(os.path.join(out, "selection-3.tsv")))

    def test_refuses_when_dtd_missing(self):
        raw = make_raw_dir(self.tmp, read(FIXTURE_XML))
        os.remove(os.path.join(raw, "dblp.dtd"))
        code, _, err = self.run_select(raw, os.path.join(self.tmp, "out"), "1,3")
        self.assertEqual(code, 3)

    def test_validation_detects_tampered_tsv(self):
        raw = make_raw_dir(self.tmp, read(FIXTURE_XML))
        out = os.path.join(self.tmp, "out")
        self.assertEqual(self.run_select(raw, out, "1,3")[0], 0)
        p = os.path.join(out, "selection-3.tsv")
        data = read(p).decode("utf-8").replace("Second paper.", "Second paper!")
        with open(p, "wb") as f:
            f.write(data.encode("utf-8"))
        code, _, err = quiet_main(dblp_validate.main, ["--out-dir", out])
        self.assertEqual(code, 4)
        self.assertIn("mismatch", err)

    def test_sample_is_deterministic_subset_of_smallest_ranks(self):
        recs = synthetic_records({2010: 30, 2011: 30})
        raw = self.generated_raw("s", recs)
        out = os.path.join(self.tmp, "out")
        self.assertEqual(self.run_select(raw, out, "10,40")[0], 0)
        sample = os.path.join(self.tmp, "samples", "dblp", "dblp-sample-5.xml")
        args = ["--selection-dir", out, "--label", "10", "--n", "5", "--out", sample]
        code, _, err = quiet_main(dblp_sample.main, args)
        self.assertEqual(code, 0, err)
        rows, count, _ = ds.parse_extract(sample)
        keys10 = ds.read_tsv(os.path.join(out, "selection-10.tsv"))
        want = sorted(keys10, key=lambda k: ds.rank_of("test-seed", k))[:5]
        self.assertEqual(set(rows), set(want))
        self.assertTrue(os.path.isfile(os.path.join(os.path.dirname(sample), "dblp.dtd")))
        self.assertEqual(quiet_main(dblp_sample.main, args)[0], 2)
        code, _, err = quiet_main(dblp_validate.main, ["--out-dir", out, "--sample", sample, "--sample-of", "10", "--sample-n", "5"])
        self.assertEqual(code, 0, err)


if __name__ == "__main__":
    unittest.main()
