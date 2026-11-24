# -*- coding: utf-8 -*-
"""Self-tests for enrich_common.py, enrich_reconcile.py, enrich_sample.py, enrich_fetch.py, validate_manifest.py.

Owner: dataset-engineer. ALL DATA IN THIS FILE IS SYNTHETIC: DOIs use the
10.9999/SYNTHETIC.* prefix (plus one fake 10.48550 arXiv-style DOI), names
are invented, dates are 2030-01-01, the contact address is
synthetic-contact@example.invalid. No network: enrich_fetch.py is exercised
only in dry-run mode or with an in-process fake HTTP getter.
Run with:
    python3 -I -B -m unittest discover -s datasets/scripts/tests -v
"""

import contextlib
import email.message
import io
import json
import os
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.dirname(HERE)
FIXTURES = os.path.join(HERE, "fixtures")
if SCRIPTS not in sys.path:
    sys.path.insert(0, SCRIPTS)

import dblp_select as ds  # noqa: E402
import enrich_common as ec  # noqa: E402
import enrich_fetch as ef  # noqa: E402
import enrich_reconcile as er  # noqa: E402
import enrich_sample as es  # noqa: E402
import validate_manifest as vm  # noqa: E402

SYN_DATE = "2030-01-01"
SYN_CONTACT = "synthetic-contact@example.invalid"
VALID_ORCID = "0000-0002-1825-0097"  # ORCID's documented example iD (valid checksum)
VALID_ORCID_X = "0000-0002-1694-233X"  # documented example with X check character
BAD_ORCID = "0000-0002-1825-0098"

# Synthetic DBLP works: key -> (doi, title, [(author, orcid attr or None)], in_small)
WORKS = [
    ("syn/w1", "10.9999/synthetic.1", "Synthetic Graph Paper One.", [("Anna K&ouml;nig 0001", VALID_ORCID), ("Bo Cafe", None)], True),
    ("syn/w2", "10.9999/synthetic.2", "Synthetic Paper Two.", [("C One", None), ("D Two", None)], True),
    ("syn/w3", "10.9999/synthetic.3", "Synthetic Paper Three.", [("E Three", None), ("F Four", None)], False),
    ("syn/w4", "10.48550/arxiv.2030.00001", "Synthetic Preprint Four.", [("G Five", None), ("H Six", None)], True),
    ("syn/w5", "10.9999/synthetic.5", "Synthetic Alias Paper Five.", [("I Seven", None), ("J Eight", None)], False),
    ("syn/w6", "10.9999/synthetic.6", "Synthetic Paper Six.", [("K Nine", None), ("L Ten", None)], False),
]


def quiet(fn, *a, **kw):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = fn(*a, **kw)
    return code, out.getvalue(), err.getvalue()


def write(path, data):
    d = os.path.dirname(path)
    if d and not os.path.isdir(d):
        os.makedirs(d)
    with open(path, "wb") as f:
        f.write(data.encode("utf-8") if isinstance(data, str) else data)


def side(path):
    write(path + ".provenance.json", json.dumps({"output": {"sha256": ec.sha256_file(path)}, "synthetic": True}))


def xml_for(works):
    recs = []
    for key, doi, title, authors, _ in works:
        a = "".join('<author%s>%s</author>\n' % (' orcid="%s"' % o if o else "", n) for n, o in authors)
        recs.append('<article mdate="2030-01-01" key="%s">\n%s<title>%s</title>\n<year>2020</year>\n'
                    '<journal>J. Synthetic</journal>\n<ee>https://doi.org/%s</ee>\n</article>' % (key, a, title, doi))
    return ds.XML_HEADER + "\n".join(recs).encode("latin-1") + b"\n" + ds.XML_FOOTER


def write_selection(sel_dir, label, works):
    xml = os.path.join(sel_dir, "selection-%s.xml" % label)
    write(xml, xml_for(works))
    rows = ["\t".join(ds.TSV_COLUMNS)]
    for key, doi, title, authors, _ in sorted(works):
        rows.append("\t".join([key, "article", "2020", "J. Synthetic", doi, str(len(authors)), title]))
    tsv = os.path.join(sel_dir, "selection-%s.tsv" % label)
    write(tsv, "\n".join(rows) + "\n")
    dois = os.path.join(sel_dir, "selection-%s.dois.txt" % label)
    write(dois, "".join(d + "\n" for d in sorted(w[1] for w in works)))
    for p in (xml, tsv, dois):
        side(p)


def cr_body(doi, family=None, orcid=None):
    authors = [{"given": "Anna", "family": family or "König", "sequence": "first",
                "ORCID": "http://orcid.org/" + orcid if orcid else None, "authenticated-orcid": False},
               {"given": "Bo", "family": "Cafe", "sequence": "additional"}]
    authors = [dict((k, v) for k, v in a.items() if v is not None) for a in authors]
    return json.dumps({"status": "ok", "message-type": "work", "message-version": "1.0.0",
                       "message": {"DOI": doi.upper(), "title": ["SYNTHETIC"], "type": "journal-article",
                                   "author": authors, "abstract": "<p>SYNTHETIC abstract to be trimmed</p>"}})


def oa_work(wid, doi, title, authors):
    return {"id": "https://openalex.org/" + wid, "doi": "https://doi.org/" + doi, "title": title,
            "publication_year": 2020, "abstract_inverted_index": {"SYNTHETIC": [0]},
            "authorships": [{"author_position": "first", "raw_author_name": n,
                             "author": {"id": "https://openalex.org/A" + wid, "display_name": n, "orcid": o},
                             "institutions": []} for n, o in authors]}


def oa_body(works, count=None, echo=None):
    meta = {"count": len(works) if count is None else count, "per_page": 50, "page": 1}
    if echo is not None:
        meta["x_query"] = {"oqo": {"filter_rows": [{"join": "or", "filters": [
            {"column_id": "doi", "value": "https://doi.org/" + d} for d in echo]}]}}
    return json.dumps({"meta": meta, "results": works, "group_by": []})


def make_world(root):
    """Build a complete SYNTHETIC selection + Crossref + OpenAlex tree under root."""
    sel = os.path.join(root, "datasets/derived/dblp")
    os.makedirs(sel)
    shutil.copyfile(os.path.join(FIXTURES, "dblp-synthetic.dtd"), os.path.join(sel, "dblp.dtd"))
    write_selection(sel, "small", [w for w in WORKS if w[4]])
    write_selection(sel, "big", WORKS)
    req = os.path.join(root, "datasets/derived/enrichment")
    d = dict((w[0], w[1]) for w in WORKS)
    non_arxiv = sorted(w[1] for w in WORKS if not w[1].startswith("10.48550/"))
    write(os.path.join(req, "crossref-dois.txt"), "".join(x + "\n" for x in non_arxiv))
    crd = os.path.join(root, "datasets/raw/crossref/retrieved=%s" % SYN_DATE)
    os.makedirs(crd)
    stem = ec.doi_file_stem
    write(os.path.join(crd, stem(d["syn/w1"]) + ".json"), cr_body(d["syn/w1"], orcid=VALID_ORCID))
    write(os.path.join(crd, stem(d["syn/w2"]) + ".json"), '{"status": "ok", "mess')  # truncated JSON
    write(os.path.join(crd, stem(d["syn/w6"]) + ".json"), cr_body("10.9999/synthetic.other"))
    write(os.path.join(crd, "0" * 64 + ".json"), cr_body("10.9999/synthetic.orphan"))  # orphan
    logs = os.path.join(root, "datasets/raw/logs")
    rows = [(d["syn/w1"], "200"), (d["syn/w1"], "200"), (d["syn/w2"], "200"), (d["syn/w3"], "404"),
            (d["syn/w5"], "301"), (d["syn/w6"], "200")]
    write(os.path.join(logs, "crossref-%s.tsv" % SYN_DATE),
          "".join("%s\t%s\t2030-01-01T00:00:0%dZ\t%s\n" % (x, s, i, stem(x)) for i, (x, s) in enumerate(rows)))
    oad = os.path.join(root, "datasets/raw/openalex/retrieved=%s" % SYN_DATE)
    chunk_a = [d["syn/w1"], d["syn/w2"], d["syn/w3"], d["syn/w4"], d["syn/w5"]]
    chunk_b = [d["syn/w6"]]
    write(os.path.join(req, "oa-chunk-aaaa"), "".join(x + "\n" for x in chunk_a))
    write(os.path.join(req, "oa-chunk-aaab"), "".join(x + "\n" for x in chunk_b))
    works_a = [
        oa_work("W1", d["syn/w1"], "Synthetic Graph Paper One", [("Anna Konig", "https://orcid.org/" + VALID_ORCID),
                                                                  ("Zed Nobody", "https://orcid.org/" + BAD_ORCID)]),
        oa_work("W2a", d["syn/w2"], "Synthetic Paper Two", [("C One", None)]),
        oa_work("W2b", d["syn/w2"], "Synthetic Paper Two (dup)", [("D Two", "https://orcid.org/" + VALID_ORCID_X)]),
        oa_work("W4", d["syn/w4"], "Synthetic Preprint Four", [("G Five", None)]),
        oa_work("W5", "10.9999/synthetic.alias5", "Synthetic alias paper five", [("I Seven", None)]),
    ]
    write(os.path.join(oad, "oa-chunk-aaaa.json"), oa_body(works_a, echo=chunk_a))
    write(os.path.join(oad, "oa-chunk-aaab.json"),
          oa_body([oa_work("W9", "10.9999/synthetic.unrelated", "Unrelated", [])], count=2))
    write(os.path.join(logs, "openalex-%s.tsv" % SYN_DATE),
          "oa-chunk-aaaa\t200\t2030-01-01T00:00:00Z\t5\noa-chunk-aaab\t200\t2030-01-01T00:00:01Z\t1\n")
    return root


def reconcile_args(root, out=None):
    return ["--selection-dir", os.path.join(root, "datasets/derived/dblp"), "--labels", "small,big",
            "--crossref-root", os.path.join(root, "datasets/raw/crossref"),
            "--openalex-root", os.path.join(root, "datasets/raw/openalex"),
            "--logs-dir", os.path.join(root, "datasets/raw/logs"),
            "--request-dir", os.path.join(root, "datasets/derived/enrichment"),
            "--out-dir", out or os.path.join(root, "datasets/derived/enrichment"), "--root", root]


def read_tsv_dicts(path):
    with open(path, encoding="utf-8") as f:
        header = f.readline().rstrip("\n").split("\t")
        return [dict(zip(header, l.rstrip("\n").split("\t"))) for l in f]


class TempDirCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="scholarchain-synthetic-")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)
        ec.set_display_root(None)
        del ec._ROOT[:]


# ---------------------------------------------------------------------------
# enrich_common
# ---------------------------------------------------------------------------


class CommonTest(unittest.TestCase):
    def test_canonical_response_doi(self):
        self.assertEqual(ec.canonical_response_doi("https://doi.org/10.1000/ABC%2F1"), "10.1000/abc/1")
        self.assertEqual(ec.canonical_response_doi(" 10.1000/AbC "), "10.1000/abc")
        self.assertEqual(ec.canonical_response_doi("10.1000/50%25"), "10.1000/50%25")  # bare: never decoded
        self.assertIsNone(ec.canonical_response_doi("not-a-doi"))
        self.assertIsNone(ec.canonical_response_doi(None))
        self.assertIsNone(ec.canonical_response_doi(123))

    def test_orcid_checksum_and_normalisation(self):
        self.assertEqual(ec.normalize_orcid(VALID_ORCID), (VALID_ORCID, True))
        self.assertEqual(ec.normalize_orcid("https://orcid.org/" + VALID_ORCID), (VALID_ORCID, True))
        self.assertEqual(ec.normalize_orcid("http://www.orcid.org/" + VALID_ORCID), (VALID_ORCID, True))
        self.assertEqual(ec.normalize_orcid("0000-0002-1694-233x"), (VALID_ORCID_X, True))
        self.assertEqual(ec.normalize_orcid("0000000218250097"), (VALID_ORCID, True))
        self.assertEqual(ec.normalize_orcid(BAD_ORCID), (BAD_ORCID, False))
        self.assertEqual(ec.normalize_orcid("0000-0002-1825"), (None, False))
        self.assertEqual(ec.normalize_orcid(""), (None, False))
        self.assertEqual(ec.normalize_orcid(None), (None, False))
        self.assertEqual(ec.orcid_check_digit("000000021694233"), "X")

    def test_name_tokens_and_surname_heuristic(self):
        self.assertEqual(ec.name_tokens("Anna König 0001"), ["anna", "konig"])
        self.assertEqual(ec.name_tokens("José Martínez-López"), ["jose", "martinez", "lopez"])
        self.assertEqual(ec.surname_heuristic(["konig"], ["Anna König 0001", "Bo Cafe"]), ("surname_exact", "Anna König 0001"))
        self.assertEqual(ec.surname_heuristic(["anna"], ["Anna König"]), ("surname_token", "Anna König"))
        self.assertEqual(ec.surname_heuristic(["koenig"], ["Anna König"]), ("none", ""))  # known transliteration gap
        self.assertEqual(ec.surname_heuristic([], ["Anna König"]), ("none", ""))
        self.assertEqual(ec.surname_heuristic(["x"], ["X Y"]), ("none", ""))  # 1-letter surnames ignored

    def test_title_key(self):
        self.assertEqual(ec.title_key("Synthetic  Alias-Paper, Five."), "synthetic alias paper five")
        self.assertEqual(ec.title_key("Étude"), "etude")

    def test_load_json_file_errors(self):
        with tempfile.TemporaryDirectory() as t:
            p = os.path.join(t, "x.json")
            write(p, "")
            self.assertEqual(ec.load_json_file(p)[1], "empty file")
            write(p, "{bad")
            self.assertTrue(ec.load_json_file(p)[1].startswith("invalid JSON"))
            write(p, '{"a": 1}')
            self.assertEqual(ec.load_json_file(p), ({"a": 1}, None))

    def test_sha256_guard_rejects_malformed_digests(self):
        good = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        self.assertEqual(ec.checked_hex(good), good)
        for bad in (good + "d096690901000000", good.upper(), good[:63], "", None):
            with self.assertRaises(ds.SelectError):
                ec.checked_hex(bad)
        self.assertEqual(ec.sha256_text("abc"), good)

    def test_tree_digest_filters(self):
        with tempfile.TemporaryDirectory() as t:
            write(os.path.join(t, "a.json"), "1")
            write(os.path.join(t, "b.json"), "22")
            write(os.path.join(t, "c.txt"), "333")
            n, total, dig = ec.tree_digest(t, ".json")
            self.assertEqual((n, total), (2, 3))
            self.assertEqual(ec.tree_digest(t, None, "c")[:2], (1, 3))
            write(os.path.join(t, "b.json"), "23")
            self.assertNotEqual(ec.tree_digest(t, ".json")[2], dig)


# ---------------------------------------------------------------------------
# enrich_reconcile
# ---------------------------------------------------------------------------


class ReconcileTest(TempDirCase):
    def setUp(self):
        super(ReconcileTest, self).setUp()
        make_world(self.tmp)
        self.out = os.path.join(self.tmp, "datasets/derived/enrichment")
        code, self.stdout, err = quiet(er.main, reconcile_args(self.tmp))
        self.assertEqual(code, 0, err)
        with open(os.path.join(self.out, "reconcile-summary.json"), encoding="utf-8") as f:
            self.s = json.load(f)

    def test_crossref_log_reconciliation(self):
        run = self.s["crossref"]["runs"][0]
        self.assertEqual(run["log_rows"], 6)
        self.assertEqual(run["log_distinct_dois"], 5)
        self.assertEqual(run["log_duplicate_doi_rows"], 1)
        self.assertEqual(run["log_status_counts"], {"200": 4, "301": 1, "404": 1})
        self.assertEqual(run["response_files"], 4)
        self.assertEqual(run["orphan_response_files"], 1)
        self.assertEqual(run["log_hash_column_mismatch"], 0)
        self.assertEqual(run["per_doi_category_counts"],
                         {"verified": 1, "unparseable": 1, "doi_mismatch": 1, "http_error": 2})
        self.assertEqual(run["doi_mismatch_examples"], [["10.9999/synthetic.6", "10.9999/synthetic.other"]])
        self.assertEqual(self.s["crossref"]["first_run_input_check"]["input_not_in_any_log"], 0)

    def test_openalex_reconciliation(self):
        run = self.s["openalex"]["runs"][0]
        self.assertEqual(run["truncated_chunks"], [["oa-chunk-aaab", 2, 1]])
        self.assertEqual(run["results_doi_not_in_request"], 2)
        self.assertEqual(run["requested_dois_with_multiple_works"], 1)
        self.assertEqual(run["requested_dois_alias_title_match"], 1)
        self.assertEqual(run["chunk_counts"].get("request_echo_absent"), 1)
        self.assertEqual(run["request_echo_mismatch_chunks"], [])
        cats = dict((r["doi"], r["category"]) for r in read_tsv_dicts(os.path.join(self.out, "openalex-doi-status.tsv")))
        self.assertEqual(cats, {
            "10.9999/synthetic.1": "verified", "10.9999/synthetic.2": "verified", "10.9999/synthetic.3": "not_found",
            "10.48550/arxiv.2030.00001": "verified", "10.9999/synthetic.5": "alias_title_match",
            "10.9999/synthetic.6": "unknown_truncated"})

    def test_match_tables_keep_fetched_and_verified_separate(self):
        big = self.s["matches"]["big"]
        self.assertEqual(big["selected_distinct_dois"], 6)
        self.assertEqual(big["crossref"]["requested"], 5)
        self.assertEqual(big["crossref"]["not_requested_arxiv"], 1)
        self.assertEqual(big["crossref"]["fetched_http200_with_file"], 3)
        self.assertEqual(big["crossref"]["verified_match"], 1)
        self.assertEqual(big["openalex"]["verified_match"], 3)
        self.assertEqual(big["openalex"]["alias_title_match_not_counted_as_verified"], 1)
        self.assertEqual(big["union_verified"], 3)
        self.assertEqual(big["intersection_verified"], 1)
        self.assertEqual(big["neither_verified"], 3)
        small = self.s["matches"]["small"]
        self.assertEqual(small["selected_distinct_dois"], 3)
        self.assertEqual(small["union_verified"], 3)
        self.assertEqual(small["intersection_verified"], 1)

    def test_retry_lists(self):
        def lines(n):
            return ec.read_lines(os.path.join(self.out, n)) if os.path.getsize(os.path.join(self.out, n)) else []
        self.assertEqual(lines("crossref-retry-dois.txt"),
                         ["10.9999/synthetic.2", "10.9999/synthetic.5", "10.9999/synthetic.6"])
        self.assertEqual(lines("crossref-404-dois.txt"), ["10.9999/synthetic.3"])
        self.assertEqual(lines("openalex-retry-dois.txt"), ["10.9999/synthetic.6"])

    def test_orcid_candidates(self):
        links = read_tsv_dicts(os.path.join(self.out, "orcid-candidate-links.tsv"))
        self.assertEqual(len(links), 4)
        by = dict(((l["orcid"] or l["orcid_raw"], l["source"]), l) for l in links)
        cr = by[(VALID_ORCID, "crossref")]
        self.assertEqual((cr["name_heuristic"], cr["dblp_asserts_same_orcid"], cr["in_small"] if "in_small" in cr else cr["in_10k"]),
                         ("surname_exact", "true", "true"))
        oa = by[(VALID_ORCID, "openalex")]
        self.assertEqual(oa["name_heuristic"], "surname_exact")
        bad = by[(BAD_ORCID, "openalex")]
        self.assertEqual((bad["checksum_valid"], bad["name_heuristic"]), ("false", "none"))
        x = by[(VALID_ORCID_X, "openalex")]
        self.assertEqual((x["dblp_key"], x["name_heuristic"]), ("syn/w2", "surname_exact"))
        cands = read_tsv_dicts(os.path.join(self.out, "orcid-candidates.tsv"))
        # both valid iDs link to a small-selection work with surname agreement -> tie broken by iD; invalid last
        self.assertEqual([c["orcid"] for c in cands], [VALID_ORCID_X, VALID_ORCID, BAD_ORCID])
        cands = sorted(cands, key=lambda c: c["orcid"] != VALID_ORCID)
        self.assertEqual(cands[0]["sources"], "crossref|openalex")
        self.assertEqual(ec.read_lines(os.path.join(self.out, "orcid-candidates.txt")), [VALID_ORCID_X, VALID_ORCID])
        st = self.s["orcid_candidates"]
        self.assertEqual((st["distinct_orcids_all"], st["distinct_orcids_checksum_valid"]), (3, 2))
        self.assertEqual(st["dblp_asserted_orcids_big"] if "dblp_asserted_orcids_big" in st else st["dblp_asserted_orcids_50k"], 1)

    def test_outputs_have_provenance_and_are_deterministic(self):
        names = ["reconcile-summary.json", "crossref-doi-status.tsv", "openalex-doi-status.tsv",
                 "orcid-candidate-links.tsv", "orcid-candidates.tsv", "orcid-candidates.txt"]
        first = dict((n, ec.sha256_file(os.path.join(self.out, n))) for n in names)
        for n in names:
            ds.check_sidecar(os.path.join(self.out, n))
        code, _, err = quiet(er.main, reconcile_args(self.tmp))
        self.assertEqual(code, 0, err)
        self.assertEqual(first, dict((n, ec.sha256_file(os.path.join(self.out, n))) for n in names))

    def test_later_retrieval_improves_state(self):
        crd = os.path.join(self.tmp, "datasets/raw/crossref/retrieved=2030-01-02")
        os.makedirs(crd)
        doi = "10.9999/synthetic.2"
        write(os.path.join(crd, ec.doi_file_stem(doi) + ".json"), cr_body(doi))
        write(os.path.join(self.tmp, "datasets/raw/logs/crossref-2030-01-02.tsv"),
              "%s\t200\t2030-01-02T00:00:00Z\t%s\t1\t0\t%s\t\n" % (doi, ec.doi_file_stem(doi), doi.upper()))
        code, _, err = quiet(er.main, reconcile_args(self.tmp))
        self.assertEqual(code, 0, err)
        with open(os.path.join(self.out, "reconcile-summary.json"), encoding="utf-8") as f:
            s = json.load(f)
        self.assertEqual(s["matches"]["big"]["crossref"]["verified_match"], 2)
        self.assertEqual(len(s["crossref"]["runs"]), 2)


class ReconcileFailureTest(TempDirCase):
    def test_missing_selection_exit_3(self):
        code, _, err = quiet(er.main, reconcile_args(self.tmp))
        self.assertEqual(code, 3, err)

    def test_tampered_selection_exit_4(self):
        make_world(self.tmp)
        with open(os.path.join(self.tmp, "datasets/derived/dblp/selection-big.tsv"), "a") as f:
            f.write("\n")
        code, _, err = quiet(er.main, reconcile_args(self.tmp))
        self.assertEqual(code, 4, err)


# ---------------------------------------------------------------------------
# enrich_sample
# ---------------------------------------------------------------------------


class SampleTest(TempDirCase):
    def setUp(self):
        super(SampleTest, self).setUp()
        make_world(self.tmp)
        code, _, err = quiet(er.main, reconcile_args(self.tmp))
        self.assertEqual(code, 0, err)
        sdir = os.path.join(self.tmp, "datasets/samples/dblp")
        os.makedirs(sdir)
        write(os.path.join(sdir, "dblp-sample.xml"), xml_for([w for w in WORKS if w[4]]))
        shutil.copyfile(os.path.join(FIXTURES, "dblp-synthetic.dtd"), os.path.join(sdir, "dblp.dtd"))
        enr = os.path.join(self.tmp, "datasets/derived/enrichment")
        self.out = os.path.join(self.tmp, "datasets/samples/enrichment")
        self.args = ["--dblp-sample", os.path.join(sdir, "dblp-sample.xml"),
                     "--crossref-status", os.path.join(enr, "crossref-doi-status.tsv"),
                     "--openalex-status", os.path.join(enr, "openalex-doi-status.tsv"),
                     "--links", os.path.join(enr, "orcid-candidate-links.tsv"),
                     "--out-dir", self.out, "--repo-root", self.tmp]

    def test_build_trims_and_verifies(self):
        code, out, err = quiet(es.main, ["build"] + self.args)
        self.assertEqual(code, 0, err)
        self.assertIn("status=PASS", out)
        with open(os.path.join(self.out, "crossref-sample.jsonl"), encoding="utf-8") as f:
            cr = [json.loads(l) for l in f]
        self.assertEqual([r["doi"] for r in cr], ["10.9999/synthetic.1"])
        self.assertNotIn("abstract", cr[0]["record"])
        self.assertTrue(cr[0]["raw_file"].startswith("datasets/raw/crossref/retrieved=2030-01-01/"))
        with open(os.path.join(self.out, "openalex-sample.jsonl"), encoding="utf-8") as f:
            oa = [json.loads(l) for l in f]
        self.assertEqual(sorted(r["record"]["id"].rsplit("/", 1)[1] for r in oa), ["W1", "W2a", "W2b", "W4"])
        self.assertTrue(all("abstract_inverted_index" not in r["record"] for r in oa))
        with open(os.path.join(self.out, "crossref-sample.jsonl.provenance.json"), encoding="utf-8") as f:
            meta = json.load(f)
        self.assertIs(meta["synthetic"], False)  # flag is set by the builder; this world is synthetic test data
        self.assertIn("license", meta)
        code, _, err = quiet(es.main, ["build"] + self.args)
        self.assertEqual(code, 2, "must refuse to overwrite without --force")

    def test_verify_detects_tampering(self):
        self.assertEqual(quiet(es.main, ["build"] + self.args)[0], 0)
        p = os.path.join(self.out, "openalex-sample.jsonl")
        with open(p, encoding="utf-8") as f:
            lines = f.readlines()
        rec = json.loads(lines[0])
        rec["record"]["title"] = "TAMPERED"
        lines[0] = json.dumps(rec) + "\n"
        with open(p, "w", encoding="utf-8") as f:
            f.writelines(lines)
        side(p)  # even with a matching sidecar the raw comparison must fail
        code, _, err = quiet(es.main, ["verify"] + self.args)
        self.assertEqual(code, 4, err)

    def test_verify_clean_clone_reports_skip(self):
        self.assertEqual(quiet(es.main, ["build"] + self.args)[0], 0)
        shutil.rmtree(os.path.join(self.tmp, "datasets/raw"))
        code, out, err = quiet(es.main, ["verify"] + self.args)
        self.assertEqual(code, 0, err)
        self.assertIn("status=SKIPPED-RAW-ABSENT", out)


# ---------------------------------------------------------------------------
# enrich_fetch (never touches the network)
# ---------------------------------------------------------------------------


def no_network(*a, **k):
    raise AssertionError("network access attempted in a test")


class FakeGetter(object):
    """In-process fake HTTP: maps URL substrings to a queue of (status, body, final_url, retry_after)."""

    def __init__(self, routes):
        self.routes = routes
        self.calls = []

    def __call__(self, url, headers, timeout=60):
        self.calls.append((url, headers))
        for k, queue in self.routes.items():
            if k in url:
                st, body, final, ra = queue.pop(0) if len(queue) > 1 else queue[0]
                return st, body.encode("utf-8") if isinstance(body, str) else body, final or url, ra
        return 404, b'{"SYNTHETIC": "not found"}', url, None


class FetchCase(TempDirCase):
    """Shared fixture for enrich_fetch tests (no test methods of its own)."""

    def setUp(self):
        super(FetchCase, self).setUp()
        self.env_before = os.environ.get(ef.CONTACT_ENV)
        os.environ[ef.CONTACT_ENV] = SYN_CONTACT
        self.inp = os.path.join(self.tmp, "in.txt")

    def tearDown(self):
        if self.env_before is None:
            os.environ.pop(ef.CONTACT_ENV, None)
        else:
            os.environ[ef.CONTACT_ENV] = self.env_before
        super(FetchCase, self).tearDown()

    def common(self, cmd):
        return [cmd, "--input", self.inp, "--out-root", os.path.join(self.tmp, "raw", cmd),
                "--logs-dir", os.path.join(self.tmp, "logs"), "--date", SYN_DATE, "--min-interval", "0"]

    def run_fetch(self, argv, getter):
        def go():
            try:
                return ef.run(argv, getter=getter, sleep=lambda s: None)
            except ec.EnrichError as e:
                sys.stderr.write("ERROR: %s\n" % e)
                return e.exit_code
        return quiet(go)


class FetchTest(FetchCase):
    def test_dry_run_makes_no_request(self):
        write(self.inp, "10.9999/synthetic.1\n")
        for cmd in ("crossref", "openalex"):
            code, out, _ = self.run_fetch(self.common(cmd), no_network)
            self.assertEqual(code, 0)
            self.assertIn("DRY RUN", out)
        write(self.inp, VALID_ORCID + "\n")
        code, out, _ = self.run_fetch(self.common("orcid"), no_network)
        self.assertEqual((code, "DRY RUN" in out), (0, True))

    def test_missing_contact_env_refuses_before_network(self):
        os.environ.pop(ef.CONTACT_ENV, None)
        write(self.inp, "10.9999/synthetic.1\n")
        code, _, err = self.run_fetch(self.common("crossref") + ["--confirm-network"], no_network)
        self.assertEqual(code, 2)
        self.assertIn(ef.CONTACT_ENV, err)

    def test_crossref_retry_redirect_redaction_skip_and_bound(self):
        dois = ["10.9999/synthetic.1", "10.9999/synthetic.2", "10.9999/synthetic.3"]
        write(self.inp, "".join(d + "\n" for d in dois))
        body1 = cr_body(dois[0]).replace('"SYNTHETIC"', '"SYNTHETIC echo %s"' % SYN_CONTACT)
        g = FakeGetter({
            "synthetic.1": [(429, "slow down", None, 0.0), (200, body1, "https://api.crossref.org/works/10.9999/SYNTHETIC.1X", None)],
            "synthetic.2": [(404, "Resource not found.", None, None)],
        })
        code, out, err = self.run_fetch(self.common("crossref") + ["--confirm-network", "--max-records", "2"], g)
        self.assertEqual(code, 0, err)
        self.assertEqual(len(g.calls), 3)  # 2 attempts for .1, 1 for .2; .3 excluded by --max-records
        ua = g.calls[0][1]["User-Agent"]
        self.assertIn("mailto:" + SYN_CONTACT, ua)
        self.assertNotIn(SYN_CONTACT, g.calls[0][0])  # never in the URL
        saved = os.path.join(self.tmp, "raw/crossref/retrieved=%s/%s.json" % (SYN_DATE, ec.doi_file_stem(dois[0])))
        with open(saved, "rb") as f:
            data = f.read()
        self.assertNotIn(SYN_CONTACT.encode(), data)
        self.assertIn(ef.REDACTED, data)
        with open(os.path.join(self.tmp, "logs/crossref-%s.tsv" % SYN_DATE), encoding="utf-8") as f:
            log = [l.rstrip("\n").split("\t") for l in f]
        self.assertNotIn(SYN_CONTACT, json.dumps(log))
        self.assertEqual([r[1] for r in log], ["200", "404"])
        self.assertEqual((log[0][4], log[0][5], log[0][6], log[0][7]), ("2", "1", "10.9999/SYNTHETIC.1", "contact-redacted"))
        # resume: .1 is skipped, only .2 and .3 remain
        g2 = FakeGetter({})
        code, out, _ = self.run_fetch(self.common("crossref") + ["--confirm-network"], g2)
        self.assertEqual(code, 0)
        self.assertEqual(sorted(u.rsplit("/", 1)[1] for u, _ in g2.calls),
                         ["10.9999%2Fsynthetic.2", "10.9999%2Fsynthetic.3"])

    def test_crossref_rate_limit_stops_with_75(self):
        write(self.inp, "10.9999/synthetic.1\n10.9999/synthetic.2\n")
        g = FakeGetter({"synthetic": [(429, "Too Many Requests", None, 0.0)]})
        code, _, err = self.run_fetch(self.common("crossref") + ["--confirm-network", "--retries", "1"], g)
        self.assertEqual(code, ef.EXIT_QUOTA, err)
        self.assertEqual(len(g.calls), 2)  # 1 try + 1 retry, then stop before the second DOI

    def test_openalex_plan_pagination_quota_and_resume(self):
        dois = ["10.9999/synthetic.%d" % i for i in range(1, 6)] + ["10.9999/synthetic,comma"]
        write(self.inp, "".join(d + "\n" for d in dois))
        plan = ef.plan_openalex_batches(dois, 3)
        self.assertEqual([k for _, k, _ in plan], ["oa-batch", "oa-batch", "oa-single"])
        self.assertEqual(plan, ef.plan_openalex_batches(list(reversed(dois)), 3))  # order-independent names
        b1, b2 = plan[0][2], plan[1][2]
        works1 = [oa_work("W%d" % i, d, "t", []) for i, d in enumerate(b1)]
        g = FakeGetter({
            "page=1": [(200, oa_body(works1[:2], count=3), None, None)],
            "page=2": [(200, oa_body(works1[2:], count=3), None, None)],
        })
        # batch 1 needs 2 pages; batch 2 hits the quota -> exit 75
        g.routes = {"synthetic.4": [(429, "daily budget exhausted", None, 0.0)]}
        g.routes.update({"page=1": [(200, oa_body(works1[:2], count=3), None, None)],
                         "page=2": [(200, oa_body(works1[2:], count=3), None, None)]})
        code, out, err = self.run_fetch(self.common("openalex") + ["--confirm-network", "--batch-size", "3", "--retries", "0"], g)
        self.assertEqual(code, ef.EXIT_QUOTA, err)
        rd = os.path.join(self.tmp, "raw/openalex/retrieved=%s" % SYN_DATE)
        name1 = plan[0][0]
        self.assertTrue(os.path.isfile(os.path.join(rd, name1 + ".json")))
        self.assertTrue(os.path.isfile(os.path.join(rd, name1 + ".page-2.json")))
        self.assertEqual(ec.read_lines(os.path.join(rd, name1 + ".dois")), b1)
        self.assertTrue(ef.openalex_complete([os.path.join(rd, name1 + ".json"), os.path.join(rd, name1 + ".page-2.json")]))
        # resume: batch 1 complete -> skipped; batch 2 + single fetched
        g2 = FakeGetter({"synthetic.4": [(200, oa_body([oa_work("W4", b2[0], "t", [])], count=1), None, None)],
                         "comma": [(200, json.dumps(oa_work("W6", "10.9999/synthetic,comma", "t", [])), None, None)]})
        code, out, err = self.run_fetch(self.common("openalex") + ["--confirm-network", "--batch-size", "3"], g2)
        self.assertEqual(code, 0, err)
        self.assertEqual(len(g2.calls), 2)
        self.assertIn("/works/https://doi.org/10.9999/synthetic%2Ccomma", g2.calls[1][0])

    def test_orcid_bounded_and_validates_input(self):
        write(self.inp, BAD_ORCID + "\n")
        code, _, _ = self.run_fetch(self.common("orcid"), no_network)
        self.assertEqual(code, 2)
        write(self.inp, VALID_ORCID + "\n" + VALID_ORCID_X + "\n")
        g = FakeGetter({VALID_ORCID: [(200, json.dumps({"orcid-identifier": {"path": VALID_ORCID}}), None, None)]})
        code, _, err = self.run_fetch(self.common("orcid") + ["--confirm-network", "--max-records", "1"], g)
        self.assertEqual(code, 0, err)
        self.assertEqual(len(g.calls), 1)
        self.assertTrue(g.calls[0][0].endswith("/v3.0/%s/record" % VALID_ORCID))
        self.assertTrue(os.path.isfile(os.path.join(self.tmp, "raw/orcid/retrieved=%s/%s.json" % (SYN_DATE, VALID_ORCID))))


class FetchSafetyTest(FetchCase):
    """N45: hard request budget (retries, redirects, every page) and redirect allowlist."""

    CR_1 = "https://api.crossref.org/works/10.9999%2Fsynthetic.1"

    def run_events(self):
        p = os.path.join(self.tmp, "logs", ef.RUN_LOG % SYN_DATE)
        if not os.path.isfile(p):
            return []
        with open(p, encoding="utf-8") as f:
            return [l.rstrip("\n").split("\t") for l in f]

    def source_log(self, source):
        p = os.path.join(self.tmp, "logs/%s-%s.tsv" % (source, SYN_DATE))
        if not os.path.isfile(p):
            return []
        with open(p, encoding="utf-8") as f:
            return [l.rstrip("\n").split("\t") for l in f]

    def assert_contact_only_to_allowlisted_https(self, g):
        for u, headers in g.calls:
            parts = ef.urllib.parse.urlsplit(u)
            self.assertEqual(parts.scheme, "https", u)
            self.assertIn(parts.hostname, ef.ALLOWED_REDIRECT_HOSTS, u)

    def assert_contact_not_on_disk(self):
        for dirpath, _, files in os.walk(self.tmp):
            for n in files:
                with open(os.path.join(dirpath, n), "rb") as f:
                    self.assertNotIn(SYN_CONTACT.encode(), f.read(), n)

    # -- budget -------------------------------------------------------------

    def test_default_budget_is_derived_and_bounded(self):
        self.assertEqual(ef.default_max_requests(2), 7)
        self.assertEqual(ef.default_max_requests(100), 125)
        args = ef.parse_args(self.common("crossref") + ["--max-records", "40"])
        self.assertEqual(args.max_requests, 50)
        for bad in ("0", "-1"):
            with self.assertRaises(ef.UsageError):
                ef.parse_args(self.common("crossref") + ["--max-requests", bad])

    def test_budget_counts_retries(self):
        dois = ["10.9999/synthetic.1", "10.9999/synthetic.2", "10.9999/synthetic.3"]
        write(self.inp, "".join(d + "\n" for d in dois))
        g = FakeGetter({"synthetic": [(503, "busy", None, 0.0)]})
        # 3 records x (1 + 5 retries) = 18 attempts without a budget; the budget caps at 4
        code, out, err = self.run_fetch(self.common("crossref") + ["--confirm-network", "--max-records", "3",
                                                                   "--max-requests", "4", "--retries", "5"], g)
        self.assertEqual(code, 0, err)
        self.assertEqual(len(g.calls), 4)
        self.assertIn("STOPPED: request budget exhausted", out)
        ev = self.run_events()
        self.assertEqual([e[2] for e in ev], ["budget-exhausted"])
        self.assertEqual((ev[0][1], ev[0][3], ev[0][4], ev[0][5]), ("crossref", "4", "4", "3"))
        self.assertEqual(self.source_log("crossref"), [])  # nothing marked done -> fully resumable
        # default budget (max_records=2 -> 7) also bounds retries: 2 x 6 = 12 attempts would be possible
        g2 = FakeGetter({"synthetic": [(503, "busy", None, 0.0)]})
        code, out, err = self.run_fetch(self.common("crossref") + ["--confirm-network", "--max-records", "2"], g2)
        self.assertEqual(code, 0, err)
        self.assertEqual(len(g2.calls), 7)
        # resume: the unfinished DOI is requested again
        g3 = FakeGetter({"synthetic": [(200, cr_body("10.9999/synthetic.1"), None, None)]})
        code, _, err = self.run_fetch(self.common("crossref") + ["--confirm-network", "--max-records", "1"], g3)
        self.assertEqual(code, 0, err)
        self.assertEqual(len(g3.calls), 1)
        self.assertTrue(g3.calls[0][0].endswith("synthetic.1"))
        self.assert_contact_not_on_disk()

    def test_budget_counts_redirect_hops(self):
        write(self.inp, "10.9999/synthetic.1\n")
        g = FakeGetter({"synthetic.1": [(301, "", "https://api.crossref.org/works/10.9999%2FSYNTHETIC.1X", None)]})
        code, out, err = self.run_fetch(self.common("crossref") + ["--confirm-network", "--max-requests", "1"], g)
        self.assertEqual(code, 0, err)
        self.assertEqual(len(g.calls), 1)
        self.assertEqual([e[2] for e in self.run_events()], ["budget-exhausted"])

    def test_budget_covers_first_page(self):
        dois = ["10.9999/synthetic.%d" % i for i in range(1, 7)]
        write(self.inp, "".join(d + "\n" for d in dois))
        plan = ef.plan_openalex_batches(dois, 2)
        self.assertEqual(len(plan), 3)
        routes = {}
        for i, (_, _, b) in enumerate(plan):
            routes[ef.urllib.parse.quote(b[0], safe="")] = [
                (200, oa_body([oa_work("W%d%d" % (i, j), d, "t", []) for j, d in enumerate(b)], count=2), None, None)]
        g = FakeGetter(routes)
        # 3 batches planned (max-records 5), each needs exactly one page; the budget admits only 2 requests
        code, out, err = self.run_fetch(self.common("openalex") + ["--confirm-network", "--batch-size", "2",
                                                                   "--max-records", "5", "--max-requests", "2"], g)
        self.assertEqual(code, 0, err)
        self.assertEqual(len(g.calls), 2)
        self.assertIn("STOPPED: request budget exhausted", out)
        ev = self.run_events()
        self.assertEqual(ev[-1][2], "budget-exhausted")
        self.assertIn("batch=%s page=1" % plan[2][0], ev[-1][6])
        self.assertEqual(len(self.source_log("openalex")), 2)
        # --max-requests 1: page 1 of the first batch is the only request, page 2 is refused by the budget
        shutil.rmtree(os.path.join(self.tmp, "raw"))
        g2 = FakeGetter({"page=1": [(200, oa_body([oa_work("W1", plan[0][2][0], "t", [])], count=2), None, None)],
                         "page=2": [(200, oa_body([oa_work("W2", plan[0][2][1], "t", [])], count=2), None, None)]})
        code, out, err = self.run_fetch(self.common("openalex") + ["--confirm-network", "--batch-size", "2",
                                                                   "--max-requests", "1"], g2)
        self.assertEqual(code, 0, err)
        self.assertEqual(len(g2.calls), 1)
        self.assertTrue(g2.calls[0][0].endswith("&page=1"))
        self.assertIn("request budget exhausted; incomplete", self.source_log("openalex")[-1][5])
        # resume: the incomplete batch is fetched again from page 1
        g3 = FakeGetter(dict(g2.routes))
        code, _, err = self.run_fetch(self.common("openalex") + ["--confirm-network", "--batch-size", "2",
                                                                 "--max-records", "1"], g3)
        self.assertEqual(code, 0, err)
        self.assertEqual([u.rsplit("&", 1)[1] for u, _ in g3.calls], ["page=1", "page=2"])

    # -- redirects ----------------------------------------------------------

    def test_redirect_to_foreign_host_refused_without_contact(self):
        foreign = ["https://evil.example.invalid/works/x",
                   "https://api.crossref.org.evil.example.invalid/works/x",  # lookalike suffix
                   "https://api.crossref.org@evil.example.invalid/works/x",  # userinfo trick
                   "https://api.crossref.org:8443/works/x"]  # non-default port
        for target in foreign:
            shutil.rmtree(os.path.join(self.tmp, "logs"), ignore_errors=True)
            write(self.inp, "10.9999/synthetic.1\n")
            g = FakeGetter({"synthetic.1": [(302, "", target, None)],
                            "evil": [(200, cr_body("10.9999/synthetic.1"), None, None)]})
            code, out, err = self.run_fetch(self.common("crossref") + ["--confirm-network"], g)
            self.assertEqual(code, 0, err)
            self.assertEqual([u for u, _ in g.calls], [self.CR_1], target)  # foreign URL never requested
            self.assert_contact_only_to_allowlisted_https(g)
            log = self.source_log("crossref")
            self.assertEqual(log[0][1], "302")
            self.assertIn("redirect-refused", log[0][7])
            ev = [e for e in self.run_events() if e[2] == "redirect-refused"]
            self.assertEqual(len(ev), 1)
            self.assertNotIn("/works/x", ev[0][6])  # scheme://host only
            self.assertFalse(os.path.isdir(os.path.join(self.tmp, "raw/crossref/retrieved=%s" % SYN_DATE)))
        self.assert_contact_not_on_disk()

    def test_https_to_http_downgrade_refused(self):
        write(self.inp, "10.9999/synthetic.1\n")
        g = FakeGetter({"synthetic.1": [(301, "", "http://api.crossref.org/works/10.9999%2FSYNTHETIC.1X", None)],
                        "SYNTHETIC.1X": [(200, cr_body("10.9999/synthetic.1"), None, None)]})
        code, _, err = self.run_fetch(self.common("crossref") + ["--confirm-network"], g)
        self.assertEqual(code, 0, err)
        self.assertEqual(len(g.calls), 1)
        self.assert_contact_only_to_allowlisted_https(g)
        log = self.source_log("crossref")
        self.assertEqual(log[0][1], "301")
        self.assertIn("redirect-refused http://api.crossref.org (non-https scheme http)", log[0][7])
        self.assertEqual([e[2] for e in self.run_events()], ["redirect-refused", "run-complete"])
        # the OpenAlex path refuses the same way and notes it in the batch log row
        write(self.inp, "10.9999/synthetic.2\n")
        g2 = FakeGetter({"page=1": [(308, "", "http://api.openalex.org/works?page=1", None)]})
        code, _, err = self.run_fetch(self.common("openalex") + ["--confirm-network"], g2)
        self.assertEqual(code, 0, err)
        self.assertEqual(len(g2.calls), 1)
        self.assertIn("redirect-refused http://api.openalex.org", self.source_log("openalex")[0][5])

    def test_same_host_redirect_allowed(self):
        write(self.inp, "10.9999/synthetic.1\n")
        target = "https://api.crossref.org/works/10.9999%2FSYNTHETIC.1X"
        g = FakeGetter({"synthetic.1": [(301, "", target, None)],
                        "SYNTHETIC.1X": [(200, cr_body("10.9999/synthetic.1"), None, None)]})
        code, _, err = self.run_fetch(self.common("crossref") + ["--confirm-network"], g)
        self.assertEqual(code, 0, err)
        self.assertEqual([u for u, _ in g.calls], [self.CR_1, target])
        self.assertIn("mailto:" + SYN_CONTACT, g.calls[1][1]["User-Agent"])
        log = self.source_log("crossref")
        self.assertEqual((log[0][1], log[0][4], log[0][5], log[0][6]), ("200", "2", "1", "10.9999/SYNTHETIC.1"))
        self.assertTrue(os.path.isfile(os.path.join(self.tmp, "raw/crossref/retrieved=%s/%s.json"
                                                    % (SYN_DATE, ec.doi_file_stem("10.9999/synthetic.1")))))
        self.assertEqual([e[2] for e in self.run_events()], ["run-complete"])
        self.assert_contact_not_on_disk()

    def test_redirect_hop_limit(self):
        write(self.inp, "10.9999/synthetic.1\n")
        calls = []

        def g(url, headers, timeout=60):  # endless same-host redirect chain ?n=1, ?n=2, ...
            calls.append(url)
            return 307, b"", self.CR_1 + "?n=%d" % len(calls), None
        g.calls = calls
        code, _, err = self.run_fetch(self.common("crossref") + ["--confirm-network"], g)
        self.assertEqual(code, 0, err)
        self.assertEqual(len(g.calls), 1 + ef.MAX_REDIRECT_HOPS)
        self.assertIn("redirect hop limit", self.source_log("crossref")[0][7])

    def test_http_get_never_auto_follows_and_resolves_location(self):
        """The real transport (urllib) is stubbed: no socket is opened."""
        seen = {}

        class FakeOpener(object):
            def __init__(self, handlers):
                self.handlers = handlers

            def open(self, req, timeout=None):
                seen["url"] = req.full_url
                hdrs = email.message.Message()
                hdrs["Location"] = seen["location"]
                raise ef.urllib.error.HTTPError(req.full_url, seen["code"], "Moved", hdrs, io.BytesIO(b""))

        def build_opener(*handlers):
            seen["handlers"] = handlers
            return FakeOpener(handlers)

        orig = ef.urllib.request.build_opener
        ef.urllib.request.build_opener = build_opener
        try:
            for code, loc, expect in ((301, "/works/10.9999%2FSYNTHETIC.1X", "https://api.crossref.org/works/10.9999%2FSYNTHETIC.1X"),
                                      (308, "http://evil.example.invalid/x", "http://evil.example.invalid/x")):
                seen.update(code=code, location=loc)
                st, body, final, ra = ef.http_get(self.CR_1, {"User-Agent": "x"})
                self.assertEqual((st, final, ra), (code, expect, None))
                self.assertTrue(any(isinstance(h, ef._NoRedirects) for h in seen["handlers"]))
        finally:
            ef.urllib.request.build_opener = orig
        h = ef._NoRedirects()
        self.assertIsNone(h.redirect_request(None, None, 301, "Moved", {}, "https://evil.example.invalid/"))


# ---------------------------------------------------------------------------
# validate_manifest
# ---------------------------------------------------------------------------


SYN_MANIFEST = u"""# SYNTHETIC manifest for tests
schema_version: "9.9.9"
datasets:
  - name: syn  # comment
    status: "SYNTHETIC # not a comment"
    license: CC0
    owning_agent: dataset-engineer
    documentation_url: "https://example.invalid/"
    notes: >-
      folded line one
      line two

      new paragraph
    literal: |
      a
      b
    flow: {a: 1, b: "x, y"}
    lst: [1, 2.5, true, null]
    files:
      - path: "data/a.txt"
        kind: file
        tracked: true
        size_bytes: 6
        sha256: "%(sha)s"
        lines: 2
      - path: "raw/absent.bin"
        kind: file
        tracked: false
        size_bytes: 1
      - path: "data"
        kind: dir
        suffix: ".txt"
        file_count: 1
      - path: "data/s.json"
        kind: json
        values:
          - json_pointer: "/m/n"
            expected: 5
  - name: nolicense
    status: x
    owning_agent: dataset-engineer
"""


class ManifestTest(TempDirCase):
    def setUp(self):
        super(ManifestTest, self).setUp()
        write(os.path.join(self.tmp, "data/a.txt"), "x\nyyy\n")
        write(os.path.join(self.tmp, "data/s.json"), '{"m": {"n": 5}}')
        self.man = os.path.join(self.tmp, "m.yaml")
        write(self.man, SYN_MANIFEST % {"sha": ec.sha256_file(os.path.join(self.tmp, "data/a.txt"))})

    def test_yaml_subset(self):
        with open(self.man, encoding="utf-8") as f:
            d = vm.load_yaml_subset(f.read())
        e = d["datasets"][0]
        self.assertEqual(e["status"], "SYNTHETIC # not a comment")
        self.assertEqual(e["notes"], "folded line one line two\nnew paragraph")
        self.assertEqual(e["literal"], "a\nb\n")
        self.assertEqual(e["flow"], {"a": 1, "b": "x, y"})
        self.assertEqual(e["lst"], [1, 2.5, True, None])
        self.assertEqual(e["files"][0]["size_bytes"], 6)
        self.assertEqual(e["files"][3]["values"][0], {"json_pointer": "/m/n", "expected": 5})
        with self.assertRaises(vm.ManifestError):
            vm.load_yaml_subset("a: 1\na: 2\n")
        with self.assertRaises(vm.ManifestError):
            vm.load_yaml_subset("a: &x 1\n")

    def test_real_manifest_parses(self):
        path = os.path.join(SCRIPTS, os.pardir, "dataset-manifest.yaml")
        with open(path, encoding="utf-8") as f:
            d = vm.load_yaml_subset(f.read())
        names = [x["name"] for x in d["datasets"]]
        for n in ("dblp", "crossref", "openalex", "orcid"):
            self.assertIn(n, names)
        for x in d["datasets"]:
            self.assertTrue(x.get("license"), x["name"])

    def test_validator_pass_skip_fail(self):
        code, out, _ = quiet(vm.main, ["--manifest", self.man, "--root", self.tmp])
        self.assertEqual(code, 4)  # 'nolicense' entry fails
        self.assertIn("SKIP syn", out)
        self.assertIn("missing required keys: license", out)
        self.assertEqual(out.count("FAIL"), 2)  # one FAIL line + the summary status
        write(os.path.join(self.tmp, "data/a.txt"), "x\nyyz\n")
        code, out, _ = quiet(vm.main, ["--manifest", self.man, "--root", self.tmp])
        self.assertIn("sha256 expected", [l for l in out.splitlines() if l.startswith("FAIL")][0])
        os.remove(os.path.join(self.tmp, "data/a.txt"))
        code, out, _ = quiet(vm.main, ["--manifest", self.man, "--root", self.tmp])
        self.assertIn("committed (tracked) file is missing", out)

    def test_bad_manifest_exit_2(self):
        write(self.man, "datasets: 5\n")
        self.assertEqual(quiet(vm.main, ["--manifest", self.man, "--root", self.tmp])[0], 2)


if __name__ == "__main__":
    unittest.main()
