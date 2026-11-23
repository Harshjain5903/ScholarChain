#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""dblp_select.py -- deterministic, seeded, year-stratified selection of real DBLP works.

Owner: dataset-engineer (datasets/**)
Provenance: ScholarChain dataset acquisition pipeline, remediation Phase 03.

What it does (no network access, standard library only, Python >= 3.8):

  1. Verifies <raw-dir>/dblp.xml.gz and <raw-dir>/dblp.dtd against
     <raw-dir>/SHA256SUMS and refuses to continue on any mismatch.
  2. Pass 1: stream-parses dblp.xml.gz with pyexpat (never loads it into
     memory), resolving DBLP named character entities from the LOCAL dblp.dtd
     only. Counts every top-level record type, applies the eligibility rules in
     a fixed order (first failing reason counted), and writes an on-disk
     sqlite index in <out-dir>/.work/ (deleted on success).
  3. De-duplicates DOIs in the eligible pool (tie rule: smallest rank wins),
     allocates each requested size proportionally to the per-year pool counts
     (largest remainder, ties broken by ascending year, smaller sizes capped by
     the next larger size), and within each year takes the records with the
     smallest sha256(seed + "|" + key). Smaller selections are therefore
     subsets of larger ones (asserted).
  4. Pass 2: copies the ORIGINAL bytes of the selected records out of the
     decompressed stream (no re-serialisation) into selection-<label>.xml.
  5. Writes TSVs, DOI lists, summaries, a copy of dblp.dtd and a provenance
     sidecar (<output>.provenance.json) per output, then validates every
     extract with a full entity-resolving parse against its TSV.

Exit codes: 0 ok; 2 usage error or refusal to overwrite; 3 checksum mismatch
or missing input; 4 validation failure; 1 any other error.
"""

from __future__ import print_function

import argparse
import collections
import datetime
import gzip
import hashlib
import json
import os
import re
import shutil
import sqlite3
import struct
import subprocess
import sys
import time
import urllib.parse
from xml.parsers import expat

SCRIPT_NAME = "dblp_select.py"
SCRIPT_VERSION = "1.0.0"
DEFAULT_SEED = "scholarchain-dblp-2026-11-16"
DEFAULT_SIZES = "10000,50000"
CHUNK_SIZE = 1 << 20

RAW_XML_NAME = "dblp.xml.gz"
RAW_DTD_NAME = "dblp.dtd"
SUMS_NAME = "SHA256SUMS"

XML_HEADER = (
    b'<?xml version="1.0" encoding="ISO-8859-1"?>\n'
    b'<!DOCTYPE dblp SYSTEM "dblp.dtd">\n'
    b"<dblp>\n"
)
XML_FOOTER = b"</dblp>\n"

ELIGIBLE_TYPES = ("article", "inproceedings")
VENUE_FIELD = {"article": "journal", "inproceedings": "booktitle"}

# Eligibility reasons in evaluation order. The first failing reason is counted.
REASONS = (
    "type_not_article_or_inproceedings",
    "key_missing",
    "key_duplicate",
    "title_empty",
    "year_not_numeric",
    "fewer_than_2_authors",
    "no_doi_org_ee",
    "doi_invalid",
)

RULE_TEXT = (
    "Eligibility (fixed order, first failing reason counted): "
    "(1) top-level record type is article or inproceedings; "
    "(2) key attribute present and non-empty; "
    "(3) key unique among all top-level records in the dump; "
    "(4) title text (all character data inside <title>, inline markup "
    "flattened, entities resolved) non-empty after trimming; "
    "(5) first <year> is all ASCII digits; "
    "(6) at least 2 <author> children; "
    "(7) at least one <ee> whose value is an http(s)://[www.][dx.]doi.org/ URL "
    "(else no_doi_org_ee); "
    "(8) for the first such <ee> in document order yielding a valid DOI: canonical DOI = "
    "strip URL prefix, percent-decode, trim, lowercase; it must start with "
    "'10.' and contain no whitespace (else doi_invalid). "
    "DOI de-duplication: among eligible records sharing a canonical DOI, keep "
    "only the record with the smallest rank (rank = sha256(seed + '|' + key) "
    "hex); the others leave the pool. "
    "Selection: for each size N, allocate N across publication years in "
    "proportion to the de-duplicated pool counts using the largest-remainder "
    "method (remainder ties broken by ascending year); each smaller size is "
    "additionally capped per year by the allocation of the next larger size. "
    "Within each year take the a(year) records with the smallest rank. "
    "Never 'first N records'. Smaller selections are subsets of larger ones."
)

TSV_COLUMNS = ("key", "type", "year", "venue", "doi", "n_authors", "title")

_DOI_URL_RE = re.compile(r"^\s*https?://(?:www\.)?(?:dx\.)?doi\.org/(.*)$", re.IGNORECASE | re.DOTALL)
_YEAR_RE = re.compile(r"^[0-9]+$")
_WS_RE = re.compile(r"\s")


class SelectError(Exception):
    exit_code = 1


_SHA256_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"


def _pick_sha256():
    """Return a sha256 constructor that passes a known-answer test.

    Some local builds (observed: pyenv Python 3.8.13 linked against OpenSSL
    3.6.x) return over-long, garbage-padded hexdigests from the OpenSSL-backed
    hashlib.sha256. Fall back to the built-in _sha256 module in that case.
    """
    candidates = [hashlib.sha256]
    try:
        import _sha256  # CPython built-in implementation

        candidates.append(_sha256.sha256)
    except ImportError:
        pass
    for ctor in candidates:
        try:
            h = ctor()
            h.update(b"a")
            h.update(b"bc")
            if ctor(b"abc").hexdigest() == _SHA256_ABC and h.hexdigest() == _SHA256_ABC:
                return ctor
        except Exception:
            continue
    raise SelectError("no working sha256 implementation in this Python")


sha256 = _pick_sha256()

_HEX64_RE = re.compile(r"^[0-9a-f]{64}$")


def checked_hex(h):
    """Return h if it is exactly 64 lowercase hex characters, else raise (guards against broken hash builds)."""
    if not isinstance(h, str) or not _HEX64_RE.match(h):
        raise SelectError("computed sha256 is not 64 lowercase hex characters (got %d chars); "
                          "this Python's hash implementation is broken -- use /usr/bin/python3 -I"
                          % (len(h) if isinstance(h, str) else -1))
    return h


class UsageError(SelectError):
    exit_code = 2


class ChecksumError(SelectError):
    exit_code = 3


class ValidationError(SelectError):
    exit_code = 4


# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------


def log(msg):
    sys.stderr.write("[%s] %s\n" % (datetime.datetime.utcnow().strftime("%H:%M:%S"), msg))
    sys.stderr.flush()


def utc_now():
    return datetime.datetime.utcnow().replace(microsecond=0).isoformat() + "Z"


def sha256_file(path):
    h = sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(CHUNK_SIZE)
            if not b:
                break
            h.update(b)
    return checked_hex(h.hexdigest())


def rank_of(seed, key):
    return checked_hex(sha256((seed + "|" + key).encode("utf-8")).hexdigest())


def clean_text(s):
    """Text as written to TSV: tabs/CR/LF replaced by a space, ends trimmed."""
    return s.replace("\t", " ").replace("\r", " ").replace("\n", " ").strip()


def canonical_doi(ee):
    """Return (is_doi_org_url, canonical_doi_or_None) for one <ee> value."""
    m = _DOI_URL_RE.match(ee)
    if not m:
        return False, None
    doi = urllib.parse.unquote(m.group(1)).strip().lower()
    if not doi.startswith("10.") or _WS_RE.search(doi):
        return True, None
    return True, doi


def size_label(n):
    return "%dk" % (n // 1000) if n % 1000 == 0 else str(n)


def repo_root():
    here = os.path.dirname(os.path.abspath(__file__))
    try:
        out = subprocess.check_output(
            ["git", "-C", here, "rev-parse", "--show-toplevel"], stderr=subprocess.DEVNULL
        )
        return out.decode("utf-8").strip()
    except Exception:
        return None


def code_version():
    here = os.path.dirname(os.path.abspath(__file__))
    try:
        sha = subprocess.check_output(
            ["git", "-C", here, "rev-parse", "--short", "HEAD"], stderr=subprocess.DEVNULL
        ).decode("utf-8").strip()
        dirty = subprocess.check_output(
            ["git", "-C", here, "status", "--porcelain"], stderr=subprocess.DEVNULL
        ).decode("utf-8").strip()
        return sha + ("+uncommitted" if dirty else "")
    except Exception:
        return "unknown"


def display_path(path):
    """Repository-relative path when inside the repo, else the base name (no home dirs)."""
    ap = os.path.abspath(path)
    root = repo_root()
    if root and (ap == root or ap.startswith(root + os.sep)):
        return os.path.relpath(ap, root)
    return os.path.basename(ap)


def atomic_write_bytes(path, data):
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def write_json(path, obj):
    atomic_write_bytes(path, (json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8"))


def gzip_header_mtime(path):
    with open(path, "rb") as f:
        head = f.read(10)
    if len(head) < 10 or head[:2] != b"\x1f\x8b":
        raise SelectError("%s is not a gzip file" % path)
    mtime = struct.unpack("<I", head[4:8])[0]
    if mtime == 0:
        return None
    return datetime.datetime.utcfromtimestamp(mtime).isoformat() + "Z"


# ---------------------------------------------------------------------------
# Checksum verification
# ---------------------------------------------------------------------------


def verify_raw(raw_dir):
    """Verify dblp.xml.gz and dblp.dtd against SHA256SUMS. Returns {name: {...}}."""
    sums_path = os.path.join(raw_dir, SUMS_NAME)
    if not os.path.isfile(sums_path):
        raise ChecksumError("missing %s" % sums_path)
    expected = {}
    with open(sums_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.split(None, 1)
            if len(parts) != 2:
                raise ChecksumError("malformed line in %s: %r" % (sums_path, line))
            expected[parts[1].lstrip("*").strip()] = parts[0].lower()
    result = {}
    for name in (RAW_XML_NAME, RAW_DTD_NAME):
        path = os.path.join(raw_dir, name)
        if name not in expected:
            raise ChecksumError("%s has no entry for %s" % (sums_path, name))
        if not os.path.isfile(path):
            raise ChecksumError("missing input file %s" % path)
        actual = sha256_file(path)
        if actual != expected[name]:
            raise ChecksumError(
                "checksum mismatch for %s: expected %s, got %s" % (path, expected[name], actual)
            )
        result[name] = {"path": display_path(path), "size_bytes": os.path.getsize(path), "sha256": actual}
    return result


# ---------------------------------------------------------------------------
# Streaming record parser
# ---------------------------------------------------------------------------


class Record(object):
    __slots__ = ("tag", "attrs", "fields", "start", "end")

    def __init__(self, tag, attrs, fields, start, end):
        self.tag = tag
        self.attrs = attrs
        self.fields = fields
        self.start = start
        self.end = end


class DblpRecordStream(object):
    """Streams top-level records of a DBLP-style XML document.

    Entities are resolved from the local DTD passed in `dtd_path`; any other
    external entity, or any skipped (undeclared) entity, is an error. For each
    top-level record the callback receives a Record with absolute byte offsets
    [start, end) into the decompressed document; during the callback the
    original bytes are available through `self.raw(record)`.
    """

    FIELDS = frozenset(("title", "year", "author", "ee", "journal", "booktitle"))

    def __init__(self, dtd_path, on_record, dtd_system_id="dblp.dtd"):
        if not os.path.isfile(dtd_path):
            raise ChecksumError("local DTD not found: %s (refusing to parse without it)" % dtd_path)
        self.dtd_path = dtd_path
        self.dtd_system_id = dtd_system_id
        self.on_record = on_record
        self.type_counts = collections.Counter()
        self.max_mdate = None
        self.bytes_parsed = 0
        self._dtd_loaded = False

    # -- expat handlers ----------------------------------------------------
    def _external(self, context, base, system_id, public_id):
        if context is None and system_id is not None and os.path.basename(system_id) == self.dtd_system_id:
            sub = self._parser.ExternalEntityParserCreate(context)
            with open(self.dtd_path, "rb") as f:
                sub.ParseFile(f)
            self._dtd_loaded = True
            return 1
        raise SelectError("refusing to load external entity %r (only the local DTD is allowed)" % (system_id,))

    def _skipped(self, name, is_parameter_entity):
        raise SelectError("undeclared entity &%s; (not defined in local DTD)" % name)

    def _start(self, name, attrs):
        lvl = self._level + 1
        self._level = lvl
        if lvl == 3:
            if name in self.FIELDS:
                self._field = name
                self._parts = []
            else:
                self._field = None
        elif lvl == 2:
            start = self._parser.CurrentByteIndex
            rel = start - self._base
            tag = name.encode("ascii")
            if self._buf[rel:rel + 1 + len(tag)] != b"<" + tag:
                raise SelectError("byte offset mismatch at record start %d (%s)" % (start, name))
            self._rec_tag = name
            self._rec_attrs = attrs
            self._rec_start = start
            self._fields = {}
            self._field = None
        elif lvl == 1:
            if name != "dblp":
                raise SelectError("unexpected root element <%s>" % name)
            if not self._dtd_loaded:
                raise SelectError("document did not load the local DTD %s" % self.dtd_system_id)

    def _end(self, name):
        lvl = self._level
        self._level = lvl - 1
        if lvl == 3:
            if self._field is not None:
                fl = self._fields.get(name)
                if fl is None:
                    self._fields[name] = [u"".join(self._parts)]
                else:
                    fl.append(u"".join(self._parts))
                self._field = None
        elif lvl == 2:
            idx = self._parser.CurrentByteIndex
            rel = idx - self._base
            if self._buf[rel:rel + 2] != b"</":
                raise SelectError("self-closing or unexpected end of top-level record at %d" % idx)
            gt = self._buf.find(b">", rel)
            if gt < 0:
                raise SelectError("cannot locate end of record at %d" % idx)
            end = self._base + gt + 1
            rec = Record(name, self._rec_attrs, self._fields, self._rec_start, end)
            self.type_counts[name] += 1
            md = self._rec_attrs.get("mdate")
            if md and (self.max_mdate is None or md > self.max_mdate):
                self.max_mdate = md
            self.on_record(self, rec)
            self._last_end = end
            self._rec_start = None
            self._fields = None

    def _chars(self, data):
        if self._field is not None:
            self._parts.append(data)

    def raw(self, rec):
        """Original bytes of `rec`; valid only during the on_record callback."""
        return bytes(self._buf[rec.start - self._base:rec.end - self._base])

    # -- driver ------------------------------------------------------------
    def parse_file(self, fobj):
        p = expat.ParserCreate()
        self._parser = p
        p.buffer_text = True
        p.buffer_size = 1 << 16
        p.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_ALWAYS)
        p.ExternalEntityRefHandler = self._external
        p.SkippedEntityHandler = self._skipped
        p.StartElementHandler = self._start
        p.EndElementHandler = self._end
        p.CharacterDataHandler = self._chars
        self._level = 0
        self._field = None
        self._parts = []
        self._fields = None
        self._rec_start = None
        self._last_end = 0
        self._buf = bytearray()
        self._base = 0
        while True:
            chunk = fobj.read(CHUNK_SIZE)
            if not chunk:
                break
            self._buf += chunk
            p.Parse(chunk, False)
            self.bytes_parsed += len(chunk)
            keep = self._rec_start if self._rec_start is not None else self._last_end
            drop = keep - self._base
            if drop > 0:
                del self._buf[:drop]
                self._base += drop
        p.Parse(b"", True)
        if not self._dtd_loaded:
            raise SelectError("document did not reference the local DTD")
        self._buf = bytearray()
        return self


def open_maybe_gzip(path):
    with open(path, "rb") as f:
        magic = f.read(2)
    if magic == b"\x1f\x8b":
        return gzip.open(path, "rb")
    return open(path, "rb")


# ---------------------------------------------------------------------------
# Record classification
# ---------------------------------------------------------------------------


def first(fields, name):
    v = fields.get(name)
    return v[0] if v else None


def classify_local(tag, attrs, fields):
    """Apply all eligibility rules except key uniqueness (which needs the whole dump).

    Returns (reason_or_None, info) where info has key/type/year/venue/doi/n_authors/title.
    Rule order matches REASONS; key_duplicate is applied afterwards by the caller
    and takes precedence over every reason after key_missing.
    """
    if tag not in ELIGIBLE_TYPES:
        return "type_not_article_or_inproceedings", None
    key = (attrs.get("key") or "").strip()
    if not key:
        return "key_missing", None
    info = {"key": key, "type": tag}
    title = clean_text(u"".join(fields.get("title", [])[:1]))
    if not title:
        return "title_empty", info
    year_s = (first(fields, "year") or "").strip()
    if not _YEAR_RE.match(year_s):
        return "year_not_numeric", info
    n_authors = len(fields.get("author", []))
    if n_authors < 2:
        return "fewer_than_2_authors", info
    saw_doi_url = False
    doi = None
    for ee in fields.get("ee", []):
        is_url, canon = canonical_doi(ee)
        if is_url:
            saw_doi_url = True
            if canon is not None:
                doi = canon
                break
    if not saw_doi_url:
        return "no_doi_org_ee", info
    if doi is None:
        return "doi_invalid", info
    info.update(
        year=int(year_s),
        venue=clean_text(first(fields, VENUE_FIELD[tag]) or ""),
        doi=doi,
        n_authors=n_authors,
        title=title,
    )
    return None, info


def record_row(rec):
    """TSV-comparable tuple for a parsed record (used by validation)."""
    reason, info = classify_local(rec.tag, rec.attrs, rec.fields)
    if reason is not None:
        return None, reason
    return (
        info["key"], info["type"], str(info["year"]), info["venue"], info["doi"],
        str(info["n_authors"]), info["title"],
    ), None


# ---------------------------------------------------------------------------
# Allocation
# ---------------------------------------------------------------------------


def allocate(counts, n, caps=None):
    """Largest-remainder proportional allocation of n over counts {year: count}.

    Remainder ties are broken by ascending year. Optional caps {year: max}
    bound each year's allocation; leftover seats go round-robin in the same
    remainder order to years still below their cap.
    """
    years = sorted(counts)
    total = sum(counts[y] for y in years)
    if caps is None:
        caps = dict(counts)
    capacity = sum(min(caps.get(y, 0), counts[y]) for y in years)
    if n > capacity:
        raise SelectError("cannot allocate %d records: only %d available" % (n, capacity))
    alloc = {}
    rem = {}
    for y in years:
        cap = min(caps.get(y, 0), counts[y])
        q, r = divmod(n * counts[y], total)
        alloc[y] = min(q, cap)
        rem[y] = r
    remaining = n - sum(alloc.values())
    order = sorted(years, key=lambda y: (-rem[y], y))
    while remaining > 0:
        progressed = False
        for y in order:
            if remaining == 0:
                break
            if alloc[y] < min(caps.get(y, 0), counts[y]):
                alloc[y] += 1
                remaining -= 1
                progressed = True
        if not progressed:
            raise SelectError("allocation did not converge")
    return alloc


def allocate_nested(counts, sizes):
    """Allocate every size; each smaller size is capped by the next larger one."""
    allocs = {}
    caps = None
    for n in sorted(sizes, reverse=True):
        allocs[n] = allocate(counts, n, caps)
        caps = allocs[n]
    for small, large in zip(sorted(sizes), sorted(sizes)[1:]):
        for y in counts:
            assert allocs[small][y] <= allocs[large][y], "allocation subset violated"
    return allocs


# ---------------------------------------------------------------------------
# Pass 1: index
# ---------------------------------------------------------------------------


def build_index(xml_gz, dtd_path, db_path, seed):
    if os.path.exists(db_path):
        os.remove(db_path)
    con = sqlite3.connect(db_path)
    con.execute("PRAGMA journal_mode=OFF")
    con.execute("PRAGMA synchronous=OFF")
    con.execute("CREATE TABLE allkeys (key TEXT NOT NULL)")
    con.execute(
        "CREATE TABLE cand (key TEXT NOT NULL, type TEXT NOT NULL, local_reason TEXT, "
        "year INTEGER, venue TEXT, doi TEXT, n_authors INTEGER, title TEXT, rank TEXT, "
        "start INTEGER, end INTEGER, rec_sha TEXT)"
    )
    state = {
        "keys": [],
        "cands": [],
        "reasons": collections.Counter(),
        "type_year": collections.Counter(),
        "n": 0,
        "t0": time.time(),
    }

    def flush():
        if state["keys"]:
            con.executemany("INSERT INTO allkeys VALUES (?)", state["keys"])
            state["keys"] = []
        if state["cands"]:
            con.executemany("INSERT INTO cand VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", state["cands"])
            state["cands"] = []

    def on_record(stream, rec):
        state["n"] += 1
        key = (rec.attrs.get("key") or "").strip()
        if key:
            state["keys"].append((key,))
        reason, info = classify_local(rec.tag, rec.attrs, rec.fields)
        if rec.tag in ELIGIBLE_TYPES:
            ys = (first(rec.fields, "year") or "").strip()
            if _YEAR_RE.match(ys):
                state["type_year"][int(ys)] += 1
        if info is None:
            state["reasons"][reason] += 1
        elif reason is not None:
            state["cands"].append((info["key"], info["type"], reason) + (None,) * 9)
        else:
            raw = stream.raw(rec)
            state["cands"].append((
                info["key"], info["type"], None, info["year"], info["venue"], info["doi"],
                info["n_authors"], info["title"], rank_of(seed, info["key"]),
                rec.start, rec.end, checked_hex(sha256(raw).hexdigest()),
            ))
        if len(state["keys"]) >= 50000 or len(state["cands"]) >= 50000:
            flush()
        if state["n"] % 1000000 == 0:
            log("pass 1: %d records, %.1f GB decompressed, %.0fs" % (
                state["n"], stream.bytes_parsed / 1e9, time.time() - state["t0"]))

    stream = DblpRecordStream(dtd_path, on_record)
    with open_maybe_gzip(xml_gz) as f:
        stream.parse_file(f)
    flush()
    con.commit()
    return con, stream, state


def finish_index(con, state, seed):
    """Apply key uniqueness, DOI de-duplication. Returns summary pieces."""
    log("index: finding duplicate keys")
    con.execute("CREATE TABLE dupkeys AS SELECT key, COUNT(*) AS c FROM allkeys GROUP BY key HAVING COUNT(*) > 1")
    con.execute("CREATE INDEX dupkeys_key ON dupkeys(key)")
    dup_keys = con.execute("SELECT COUNT(*), COALESCE(SUM(c),0) FROM dupkeys").fetchone()
    reasons = state["reasons"]
    reasons["key_duplicate"] += con.execute(
        "SELECT COUNT(*) FROM cand WHERE key IN (SELECT key FROM dupkeys)").fetchone()[0]
    for r, c in con.execute(
        "SELECT local_reason, COUNT(*) FROM cand WHERE local_reason IS NOT NULL "
        "AND key NOT IN (SELECT key FROM dupkeys) GROUP BY local_reason"
    ):
        reasons[r] += c
    con.execute(
        "CREATE TABLE elig AS SELECT * FROM cand WHERE local_reason IS NULL "
        "AND key NOT IN (SELECT key FROM dupkeys)"
    )
    eligible = con.execute("SELECT COUNT(*) FROM elig").fetchone()[0]
    eligible_by_year = dict(con.execute("SELECT year, COUNT(*) FROM elig GROUP BY year").fetchall())
    log("index: de-duplicating DOIs among %d eligible records" % eligible)
    con.execute("CREATE INDEX elig_doi ON elig(doi, rank)")
    dup_groups = con.execute(
        "SELECT COUNT(*), COALESCE(SUM(c),0) FROM (SELECT COUNT(*) AS c FROM elig GROUP BY doi HAVING COUNT(*) > 1)"
    ).fetchone()
    examples = con.execute(
        "SELECT doi, GROUP_CONCAT(key, ' ') FROM (SELECT doi, key FROM elig WHERE doi IN "
        "(SELECT doi FROM elig GROUP BY doi HAVING COUNT(*) > 1 ORDER BY doi LIMIT 10) ORDER BY doi, rank) "
        "GROUP BY doi ORDER BY doi"
    ).fetchall()
    con.execute(
        "CREATE TABLE pool AS SELECT e.* FROM elig e JOIN "
        "(SELECT doi, MIN(rank) AS r FROM elig GROUP BY doi) m ON e.doi = m.doi AND e.rank = m.r"
    )
    pool = con.execute("SELECT COUNT(*) FROM pool").fetchone()[0]
    distinct_dois = con.execute("SELECT COUNT(DISTINCT doi) FROM elig").fetchone()[0]
    if pool != distinct_dois:
        raise SelectError("DOI de-duplication tie: pool %d != distinct DOIs %d" % (pool, distinct_dois))
    con.execute("CREATE INDEX pool_year_rank ON pool(year, rank)")
    pool_by_year = dict(con.execute("SELECT year, COUNT(*) FROM pool GROUP BY year").fetchall())
    con.commit()
    total_reasons = sum(reasons.values())
    return {
        "eligible": eligible,
        "eligible_by_year": eligible_by_year,
        "pool": pool,
        "pool_by_year": pool_by_year,
        "reasons": dict((r, reasons.get(r, 0)) for r in REASONS),
        "reasons_total": total_reasons,
        "duplicate_keys": {"distinct_keys": dup_keys[0], "records": dup_keys[1]},
        "doi_dedup": {
            "tie_rule": "among eligible records sharing a canonical DOI keep the one with the smallest rank "
                        "sha256(seed|key); the others are removed from the pool",
            "duplicate_doi_groups": dup_groups[0],
            "records_in_duplicate_groups": dup_groups[1],
            "records_removed": eligible - pool,
            "examples_first_10_by_doi": [{"doi": d, "keys_in_rank_order": k.split(" ")} for d, k in examples],
        },
    }


# ---------------------------------------------------------------------------
# Selection and extraction
# ---------------------------------------------------------------------------


def select(con, pool_by_year, sizes):
    allocs = allocate_nested(pool_by_year, sizes)
    largest = max(sizes)
    selected = []  # rows with membership
    for y in sorted(pool_by_year):
        a_max = allocs[largest][y]
        if a_max == 0:
            continue
        rows = con.execute(
            "SELECT key, type, year, venue, doi, n_authors, title, rank, start, end, rec_sha "
            "FROM pool WHERE year = ? ORDER BY rank, key LIMIT ?", (y, a_max)
        ).fetchall()
        for pos, row in enumerate(rows):
            labels = [n for n in sizes if pos < allocs[n][y]]
            selected.append((row, labels))
    sel = {}
    for n in sizes:
        rows = [r for r, labels in selected if n in labels]
        if len(rows) != n:
            raise SelectError("selection %d produced %d rows" % (n, len(rows)))
        dois = [r[4] for r in rows]
        if len(set(dois)) != n:
            raise SelectError("duplicate DOI inside selection %d" % n)
        sel[n] = rows
    ordered = sorted(sizes)
    subset = {}
    for small, large in zip(ordered, ordered[1:]):
        ks = set(r[0] for r in sel[small])
        kl = set(r[0] for r in sel[large])
        ok = ks <= kl
        subset["%s_subset_of_%s" % (size_label(small), size_label(large))] = ok
        if not ok:
            raise SelectError("subset property violated: %d not within %d" % (small, large))
    return allocs, sel, subset


def extract(xml_gz, sel, out_paths):
    """Copy original record bytes for every selected record, in dump order."""
    ranges = {}
    for n, rows in sel.items():
        for r in rows:
            ranges.setdefault(r[8], [r[9], r[10], r[1], set()])[3].add(n)
    order = sorted(ranges.items())
    files = dict((n, open(out_paths[n] + ".tmp", "wb")) for n in sel)
    try:
        for f in files.values():
            f.write(XML_HEADER)
        buf = bytearray()
        base = 0
        i = 0
        with open_maybe_gzip(xml_gz) as src:
            while i < len(order):
                chunk = src.read(CHUNK_SIZE)
                if not chunk:
                    raise SelectError("unexpected end of input during extraction")
                buf += chunk
                while i < len(order) and order[i][1][0] <= base + len(buf):
                    start, (end, rec_sha, tag, labels) = order[i]
                    data = bytes(buf[start - base:end - base])
                    if not data.startswith(b"<" + tag.encode("ascii")) or checked_hex(sha256(data).hexdigest()) != rec_sha:
                        raise SelectError("extracted bytes at %d do not match pass-1 record hash" % start)
                    for n in labels:
                        files[n].write(data)
                        files[n].write(b"\n")
                    i += 1
                keep = order[i][0] if i < len(order) else base + len(buf)
                drop = keep - base
                if drop > len(buf):
                    drop = len(buf)
                if drop > 0:
                    del buf[:drop]
                    base += drop
        for f in files.values():
            f.write(XML_FOOTER)
    finally:
        for f in files.values():
            f.close()
    for n in sel:
        os.replace(out_paths[n] + ".tmp", out_paths[n])


# ---------------------------------------------------------------------------
# Validation (also used by dblp_validate.py and dblp_sample.py)
# ---------------------------------------------------------------------------


def read_tsv(path):
    rows = {}
    with open(path, "r", encoding="utf-8", newline="") as f:
        header = f.readline().rstrip("\n").split("\t")
        if tuple(header) != TSV_COLUMNS:
            raise ValidationError("%s: unexpected header %r" % (path, header))
        for ln, line in enumerate(f, 2):
            parts = line.rstrip("\n").split("\t")
            if len(parts) != len(TSV_COLUMNS):
                raise ValidationError("%s:%d: expected %d columns" % (path, ln, len(TSV_COLUMNS)))
            if parts[0] in rows:
                raise ValidationError("%s:%d: duplicate key %s" % (path, ln, parts[0]))
            rows[parts[0]] = tuple(parts)
    return rows


def parse_extract(xml_path, dtd_path=None):
    """Full parse of an extract; returns ({key: row}, record_count, raw_by_key)."""
    if dtd_path is None:
        dtd_path = os.path.join(os.path.dirname(os.path.abspath(xml_path)), RAW_DTD_NAME)
    rows = {}
    raws = {}
    problems = []
    count = [0]

    def on_record(stream, rec):
        count[0] += 1
        row, reason = record_row(rec)
        if row is None:
            problems.append("record at byte %d is not eligible (%s)" % (rec.start, reason))
            return
        if row[0] in rows:
            problems.append("duplicate key %s" % row[0])
        rows[row[0]] = row
        raws[row[0]] = stream.raw(rec)

    stream = DblpRecordStream(dtd_path, on_record)
    try:
        with open(xml_path, "rb") as f:
            stream.parse_file(f)
    except expat.ExpatError as e:
        raise ValidationError("%s: XML parse error: %s" % (xml_path, e))
    if problems:
        raise ValidationError("%s: %s" % (xml_path, "; ".join(problems[:5])))
    return rows, count[0], raws


def compare_rows(xml_rows, tsv_rows, what):
    if set(xml_rows) != set(tsv_rows):
        only_x = sorted(set(xml_rows) - set(tsv_rows))[:3]
        only_t = sorted(set(tsv_rows) - set(xml_rows))[:3]
        raise ValidationError("%s: key sets differ (xml-only %r, tsv-only %r)" % (what, only_x, only_t))
    for k in sorted(xml_rows):
        if xml_rows[k] != tsv_rows[k]:
            raise ValidationError("%s: field mismatch for %s: xml=%r tsv=%r" % (what, k, xml_rows[k], tsv_rows[k]))


def validate_selection(out_dir, label, expected_n=None):
    xml_path = os.path.join(out_dir, "selection-%s.xml" % label)
    tsv_path = os.path.join(out_dir, "selection-%s.tsv" % label)
    dois_path = os.path.join(out_dir, "selection-%s.dois.txt" % label)
    xml_rows, count, _ = parse_extract(xml_path)
    tsv_rows = read_tsv(tsv_path)
    if count != len(tsv_rows):
        raise ValidationError("%s: %d XML records vs %d TSV rows" % (label, count, len(tsv_rows)))
    if expected_n is not None and count != expected_n:
        raise ValidationError("%s: expected %d records, found %d" % (label, expected_n, count))
    compare_rows(xml_rows, tsv_rows, label)
    with open(dois_path, "r", encoding="utf-8") as f:
        dois = [l.rstrip("\n") for l in f]
    if dois != sorted(set(dois)):
        raise ValidationError("%s: DOI list is not sorted and unique" % label)
    if set(dois) != set(r[4] for r in tsv_rows.values()) or len(dois) != count:
        raise ValidationError("%s: DOI list does not match TSV (%d DOIs, %d rows)" % (label, len(dois), count))
    for p in (xml_path, tsv_path, dois_path):
        check_sidecar(p)
    return {
        "label": label,
        "records": count,
        "keys_match": True,
        "years_titles_authors_venues_dois_match": True,
        "distinct_dois": len(dois),
        "xml_parse": "ok (entities resolved from local %s)" % RAW_DTD_NAME,
    }, set(tsv_rows)


def check_sidecar(path):
    side = path + ".provenance.json"
    if not os.path.isfile(side):
        raise ValidationError("missing provenance sidecar %s" % side)
    with open(side, "r", encoding="utf-8") as f:
        meta = json.load(f)
    actual = sha256_file(path)
    if meta.get("output", {}).get("sha256") != actual:
        raise ValidationError("sidecar sha256 mismatch for %s" % path)
    return actual


def validate_out_dir(out_dir, labels):
    results = []
    keysets = {}
    for label in labels:
        res, keys = validate_selection(out_dir, label)
        results.append(res)
        keysets[label] = (len(keys), keys)
    subset = {}
    ordered = sorted(keysets.items(), key=lambda kv: kv[1][0])
    for (ls, (_, ks)), (ll, (_, kl)) in zip(ordered, ordered[1:]):
        ok = ks <= kl
        subset["%s_subset_of_%s" % (ls, ll)] = ok
        if not ok:
            raise ValidationError("subset check failed: %s not within %s" % (ls, ll))
    return {"selections": results, "subset": subset}


# ---------------------------------------------------------------------------
# Provenance
# ---------------------------------------------------------------------------


def sidecar(path, inputs, seed, extra, run_meta):
    meta = {
        "output": {"path": display_path(path), "size_bytes": os.path.getsize(path), "sha256": sha256_file(path)},
        "inputs": inputs,
        "seed": seed,
        "rule": RULE_TEXT,
        "script": {"name": run_meta["script_name"], "version": run_meta["script_version"]},
        "code_version": run_meta["code_version"],
        "generated_at_utc": run_meta["generated_at_utc"],
        "producer": "dataset-engineer",
        "source_dataset": "dblp",
    }
    meta.update(extra)
    write_json(path + ".provenance.json", meta)
    return meta["output"]["sha256"]


def distribution(rows, idx):
    c = collections.Counter(r[idx] for r in rows)
    return [[k, v] for k, v in sorted(c.items(), key=lambda kv: (-kv[1], str(kv[0])))]


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def parse_args(argv):
    ap = argparse.ArgumentParser(prog=SCRIPT_NAME, description=__doc__.split("\n")[0])
    ap.add_argument("--raw-dir", required=True, help="directory with dblp.xml.gz, dblp.dtd, SHA256SUMS")
    ap.add_argument("--out-dir", required=True, help="output directory (e.g. datasets/derived/dblp)")
    ap.add_argument("--seed", default=DEFAULT_SEED, help="selection seed (default %(default)s)")
    ap.add_argument("--sizes", default=DEFAULT_SIZES, help="comma-separated selection sizes (default %(default)s)")
    ap.add_argument("--force", action="store_true", help="overwrite existing outputs")
    return ap.parse_args(argv)


def output_paths(out_dir, sizes):
    names = ["eligible-index-summary.json", "selection-summary.json", RAW_DTD_NAME]
    for n in sizes:
        lab = size_label(n)
        names += ["selection-%s.tsv" % lab, "selection-%s.dois.txt" % lab, "selection-%s.xml" % lab]
    paths = [os.path.join(out_dir, x) for x in names]
    return paths + [p + ".provenance.json" for p in paths]


def run(argv):
    t_start = time.time()
    args = parse_args(argv)  # argparse exits with status 2 on bad arguments
    try:
        sizes = sorted(set(int(s) for s in args.sizes.split(",") if s.strip()))
    except ValueError:
        raise UsageError("--sizes must be comma-separated integers")
    if not sizes or min(sizes) <= 0:
        raise UsageError("--sizes must be positive integers")
    if len(set(size_label(n) for n in sizes)) != len(sizes):
        raise UsageError("sizes map to duplicate labels")
    out_dir = args.out_dir
    existing = [p for p in output_paths(out_dir, sizes) if os.path.exists(p)]
    if existing and not args.force:
        raise UsageError("refusing to overwrite %d existing output(s), e.g. %s; use --force" % (len(existing), existing[0]))

    log("verifying checksums in %s" % args.raw_dir)
    inputs = verify_raw(args.raw_dir)
    xml_gz = os.path.join(args.raw_dir, RAW_XML_NAME)
    dtd = os.path.join(args.raw_dir, RAW_DTD_NAME)
    inputs[RAW_XML_NAME]["gzip_header_mtime_utc"] = gzip_header_mtime(xml_gz)
    input_list = [inputs[RAW_XML_NAME], inputs[RAW_DTD_NAME]]

    if not os.path.isdir(out_dir):
        os.makedirs(out_dir)
    work = os.path.join(out_dir, ".work")
    if os.path.isdir(work):
        shutil.rmtree(work)
    os.makedirs(work)
    run_meta = {
        "script_name": SCRIPT_NAME,
        "script_version": SCRIPT_VERSION,
        "code_version": code_version(),
        "generated_at_utc": utc_now(),
    }

    log("pass 1: indexing %s" % xml_gz)
    t1 = time.time()
    con, stream, state = build_index(xml_gz, dtd, os.path.join(work, "index.sqlite"), args.seed)
    pass1_s = time.time() - t1
    log("pass 1 done: %d records in %.0fs" % (state["n"], pass1_s))
    idx = finish_index(con, state, args.seed)
    if idx["reasons_total"] + idx["eligible"] != state["n"]:
        raise SelectError("reason accounting error: %d + %d != %d" % (idx["reasons_total"], idx["eligible"], state["n"]))

    allocs, sel, subset = select(con, idx["pool_by_year"], sizes)
    con.close()

    xml_paths = dict((n, os.path.join(out_dir, "selection-%s.xml" % size_label(n))) for n in sizes)
    log("pass 2: extracting original bytes for %d records" % len(set(r[0] for rows in sel.values() for r in rows)))
    t2 = time.time()
    extract(xml_gz, sel, xml_paths)
    pass2_s = time.time() - t2

    outputs = {}
    dtd_copy = os.path.join(out_dir, RAW_DTD_NAME)
    shutil.copyfile(dtd, dtd_copy)
    outputs[RAW_DTD_NAME] = sidecar(dtd_copy, [inputs[RAW_DTD_NAME]], args.seed,
                                    {"description": "verbatim copy of the local DTD so extracts parse"}, run_meta)
    for n in sizes:
        lab = size_label(n)
        rows = sorted(sel[n], key=lambda r: r[0])
        tsv_path = os.path.join(out_dir, "selection-%s.tsv" % lab)
        lines = ["\t".join(TSV_COLUMNS)]
        for r in rows:
            lines.append("\t".join([r[0], r[1], str(r[2]), r[3], r[4], str(r[5]), r[6]]))
        atomic_write_bytes(tsv_path, ("\n".join(lines) + "\n").encode("utf-8"))
        dois_path = os.path.join(out_dir, "selection-%s.dois.txt" % lab)
        dois = sorted(set(r[4] for r in rows))
        atomic_write_bytes(dois_path, ("\n".join(dois) + "\n").encode("utf-8"))
        counts = {"records": n, "distinct_dois": len(dois)}
        for p, desc in ((tsv_path, "selection TSV (%s), UTF-8, sorted by key; tabs/newlines in text replaced by spaces" % ", ".join(TSV_COLUMNS)),
                        (dois_path, "canonical DOIs, one per line, sorted, unique"),
                        (xml_paths[n], "original DBLP record bytes in dump order, ISO-8859-1, wrapped with XML declaration and DOCTYPE")):
            outputs[os.path.basename(p)] = sidecar(p, input_list, args.seed,
                                                   {"description": desc, "counts": counts, "selection_size": n}, run_meta)

    elig_summary = {
        "input": inputs,
        "seed": args.seed,
        "rule": RULE_TEXT,
        "newest_mdate_observed": stream.max_mdate,
        "decompressed_bytes": stream.bytes_parsed,
        "records_total": state["n"],
        "record_type_counts": dict(sorted(stream.type_counts.items())),
        "eligibility_first_failing_reason_counts": idx["reasons"],
        "eligibility_reason_order": list(REASONS),
        "eligible": idx["eligible"],
        "duplicate_keys": idx["duplicate_keys"],
        "doi_dedup": idx["doi_dedup"],
        "pool_after_doi_dedup": idx["pool"],
        "article_inproceedings_with_numeric_year_by_year": dict(sorted(state["type_year"].items())),
        "eligible_by_year": dict(sorted(idx["eligible_by_year"].items())),
        "pool_by_year": dict(sorted(idx["pool_by_year"].items())),
        "pass1_seconds": round(pass1_s, 1),
    }
    p = os.path.join(out_dir, "eligible-index-summary.json")
    write_json(p, elig_summary)
    outputs["eligible-index-summary.json"] = sidecar(p, input_list, args.seed, {"description": "eligibility and pool summary"}, run_meta)

    log("validating extracts")
    t3 = time.time()
    validation = validate_out_dir(out_dir, [size_label(n) for n in sizes])
    validate_s = time.time() - t3

    sel_summary = {"seed": args.seed, "rule": RULE_TEXT, "sizes": sizes, "subset": subset, "selections": {}}
    for n in sizes:
        rows = sel[n]
        sel_summary["selections"][size_label(n)] = {
            "records": len(rows),
            "by_type": dict(collections.Counter(r[1] for r in rows)),
            "allocation_by_year": dict((str(y), a) for y, a in sorted(allocs[n].items()) if a),
            "by_year": dict((str(k), v) for k, v in sorted(collections.Counter(r[2] for r in rows).items())),
            "distinct_years": len(set(r[2] for r in rows)),
            "distinct_venues": len(set(r[3] for r in rows)),
            "empty_venue": sum(1 for r in rows if not r[3]),
            "by_venue": distribution(rows, 3),
            "distinct_dois": len(set(r[4] for r in rows)),
            "duplicate_dois_in_selection": len(rows) - len(set(r[4] for r in rows)),
            "n_authors_min": min(r[5] for r in rows),
            "n_authors_max": max(r[5] for r in rows),
        }
    sel_summary["validation"] = validation
    sel_summary["output_sha256"] = dict(sorted(outputs.items()))
    sel_summary["timing_seconds"] = {
        "pass1_index": round(pass1_s, 1),
        "pass2_extract": round(pass2_s, 1),
        "validation": round(validate_s, 1),
        "total_before_summary": round(time.time() - t_start, 1),
    }
    p = os.path.join(out_dir, "selection-summary.json")
    write_json(p, sel_summary)
    sidecar(p, input_list, args.seed, {"description": "selection allocation, distributions, validation"}, run_meta)
    shutil.rmtree(work)

    print("records_total=%d" % state["n"])
    print("record_type_counts=%s" % json.dumps(elig_summary["record_type_counts"], sort_keys=True))
    print("newest_mdate=%s" % stream.max_mdate)
    print("eligible=%d pool_after_doi_dedup=%d" % (idx["eligible"], idx["pool"]))
    print("first_failing_reasons=%s" % json.dumps(idx["reasons"]))
    for n in sizes:
        s = sel_summary["selections"][size_label(n)]
        print("selection %s: records=%d distinct_years=%d distinct_venues=%d distinct_dois=%d by_type=%s" % (
            size_label(n), s["records"], s["distinct_years"], s["distinct_venues"], s["distinct_dois"],
            json.dumps(s["by_type"], sort_keys=True)))
    print("subset=%s" % json.dumps(subset, sort_keys=True))
    for name, sha in sorted(outputs.items()):
        print("sha256 %s %s" % (sha, name))
    print("validation=ok wall_seconds=%.1f" % (time.time() - t_start))
    return 0


def main(argv=None):
    try:
        return run(sys.argv[1:] if argv is None else argv)
    except SelectError as e:
        sys.stderr.write("ERROR: %s\n" % e)
        return e.exit_code


if __name__ == "__main__":
    sys.exit(main())
