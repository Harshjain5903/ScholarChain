# -*- coding: utf-8 -*-
"""enrich_common.py -- shared, offline helpers for DBLP-driven enrichment.

Owner: dataset-engineer (datasets/**)

Standard library only, Python >= 3.8, no network access. Used by
enrich_reconcile.py, enrich_sample.py, enrich_fetch.py and validate_manifest.py.

DOI canonicalisation is the SAME as dblp_select.canonical_doi (strip a
http(s)://[www.][dx.]doi.org/ prefix, percent-decode, trim, lowercase). Bare
DOIs (Crossref "DOI" field) are trimmed and lowercased only: they are never
percent-encoded in Crossref JSON, so decoding them could corrupt a DOI that
legitimately contains '%'.
"""

from __future__ import print_function

import json
import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dblp_select as ds  # noqa: E402

sha256 = ds.sha256  # known-answer-tested implementation (see dblp_select._pick_sha256)
sha256_file = ds.sha256_file
_ROOT = []


def set_display_root(root):
    """Make display_path() relative to `root` (e.g. a test directory) instead of the git root."""
    del _ROOT[:]
    _ROOT.append(os.path.abspath(root) if root else None)


def display_path(path):
    """Path relative to the display root (default: git root, cached) when inside it, else the base name."""
    if not _ROOT:
        _ROOT.append(ds.repo_root())
    root = _ROOT[0]
    ap = os.path.abspath(path)
    if root and (ap == root or ap.startswith(root + os.sep)):
        return os.path.relpath(ap, root)
    return os.path.basename(ap)


atomic_write_bytes = ds.atomic_write_bytes
utc_now = ds.utc_now
code_version = ds.code_version

ARXIV_DOI_PREFIX = "10.48550/"
_ORCID_RE = re.compile(r"([0-9]{4})-?([0-9]{4})-?([0-9]{4})-?([0-9]{3}[0-9Xx])\s*$")
_ORCID_PREFIX_RE = re.compile(r"^\s*(?:https?://)?(?:www\.|sandbox\.)?orcid\.org/", re.IGNORECASE)
_HOMONYM_RE = re.compile(r"\s+[0-9]{4}$")
_NON_ALPHA_RE = re.compile(r"[^a-z]+")


class EnrichError(Exception):
    exit_code = 1


class EnrichValidationError(EnrichError):
    exit_code = 4


checked_hex = ds.checked_hex


def sha256_text(s):
    return checked_hex(sha256(s.encode("utf-8")).hexdigest())


def doi_file_stem(doi):
    """File stem used for per-DOI Crossref responses: sha256 of the requested DOI string."""
    return sha256_text(doi)


def orcid_file_stem(orcid):
    """File stem used for per-iD ORCID responses: sha256 of the normalised iD (dddd-dddd-dddd-dddX)."""
    return sha256_text(orcid)


def canonical_response_doi(value):
    """Canonical DOI from a provider field (URL form or bare form); None if unusable."""
    if value is None:
        return None
    if not isinstance(value, str):
        return None
    is_url, canon = ds.canonical_doi(value)
    if is_url:
        return canon
    v = value.strip().lower()
    if not v.startswith("10.") or re.search(r"\s", v):
        return None
    return v


def orcid_check_digit(base15):
    """ISO 7064 MOD 11-2 check character for the first 15 digits of an ORCID iD."""
    total = 0
    for ch in base15:
        total = (total + int(ch)) * 2
    result = (12 - total % 11) % 11
    return "X" if result == 10 else str(result)


def normalize_orcid(value):
    """Return (normalized 'dddd-dddd-dddd-dddX' or None, checksum_valid bool).

    Accepts bare iDs and http(s)://[www.]orcid.org/ URLs. Anything else -> (None, False).
    """
    if not isinstance(value, str) or not value.strip():
        return None, False
    s = _ORCID_PREFIX_RE.sub("", value.strip())
    m = _ORCID_RE.match(s)
    if not m or len(s.replace("-", "").strip()) != 16:
        return None, False
    digits = "".join(m.groups()).upper()
    norm = "-".join((digits[0:4], digits[4:8], digits[8:12], digits[12:16]))
    return norm, orcid_check_digit(digits[:15]) == digits[15]


def name_tokens(name):
    """Lowercase ASCII letter tokens of a personal name (accents folded, DBLP homonym number removed)."""
    if not name:
        return []
    s = _HOMONYM_RE.sub("", name.strip())
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = s.lower().replace("ß", "ss").replace("ø", "o").replace("æ", "ae").replace("ł", "l").replace("đ", "d")
    return [t for t in _NON_ALPHA_RE.split(s) if t]


def surname_heuristic(candidate_surname_tokens, dblp_author_names):
    """Heuristic name agreement between a provider author and a DBLP author list.

    Returns ("surname_exact" | "surname_token" | "none", matched DBLP name or "").
    surname_exact: the candidate's last surname token equals the last token of a
    DBLP author name. surname_token: it equals any token of a DBLP author name
    (catches given/family order swaps). This is a data-quality heuristic only,
    NOT an entity-match decision.
    """
    if not candidate_surname_tokens:
        return "none", ""
    last = candidate_surname_tokens[-1]
    if len(last) < 2:
        return "none", ""
    for n in dblp_author_names:
        toks = name_tokens(n)
        if toks and toks[-1] == last:
            return "surname_exact", n
    for n in dblp_author_names:
        if last in name_tokens(n):
            return "surname_token", n
    return "none", ""


def title_key(title):
    """Normalised title for equality checks: accent-folded lowercase ASCII alphanumeric tokens, single-spaced."""
    if not title:
        return ""
    s = unicodedata.normalize("NFKD", title)
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    return " ".join(t for t in re.split(r"[^a-z0-9]+", s) if t)


def read_lines(path):
    with open(path, "r", encoding="utf-8") as f:
        return [l.rstrip("\n") for l in f if l.rstrip("\n")]


def load_json_file(path):
    """Return (obj, error_string). error_string is None on success."""
    try:
        with open(path, "rb") as f:
            data = f.read()
    except OSError as e:
        return None, "unreadable: %s" % e
    if not data.strip():
        return None, "empty file"
    try:
        return json.loads(data.decode("utf-8")), None
    except (ValueError, UnicodeDecodeError) as e:
        return None, "invalid JSON: %s" % str(e)[:120]


def write_json(path, obj):
    atomic_write_bytes(path, (json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode("utf-8"))


def write_tsv(path, header, rows):
    out = ["\t".join(header)]
    for r in rows:
        out.append("\t".join(clean_cell(c) for c in r))
    atomic_write_bytes(path, ("\n".join(out) + "\n").encode("utf-8"))


def clean_cell(v):
    if v is None:
        return ""
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v).replace("\t", " ").replace("\r", " ").replace("\n", " ")


def sidecar(path, inputs, extra, run_meta, source_dataset):
    meta = {
        "output": {"path": display_path(path), "size_bytes": os.path.getsize(path), "sha256": sha256_file(path)},
        "inputs": inputs,
        "script": {"name": run_meta["script_name"], "version": run_meta["script_version"]},
        "code_version": run_meta["code_version"],
        "generated_at_utc": run_meta["generated_at_utc"],
        "producer": "dataset-engineer",
        "source_dataset": source_dataset,
    }
    meta.update(extra)
    write_json(path + ".provenance.json", meta)
    return meta["output"]["sha256"]


def file_ref(path, with_sha=True):
    ref = {"path": display_path(path), "size_bytes": os.path.getsize(path)}
    if with_sha:
        ref["sha256"] = sha256_file(path)
    return ref


def tree_digest(dir_path, suffix=None, prefix=None):
    """(file_count, total_bytes, sha256 over sorted 'name<TAB>sha256<LF>' lines) for regular files in dir_path.

    Optional suffix/prefix filters select a subset of file names (the provenance sidecars and other files
    sharing a directory are then excluded).
    """
    names = sorted(n for n in os.listdir(dir_path)
                   if os.path.isfile(os.path.join(dir_path, n))
                   and (suffix is None or n.endswith(suffix)) and (prefix is None or n.startswith(prefix)))
    h = sha256()
    total = 0
    for n in names:
        p = os.path.join(dir_path, n)
        total += os.path.getsize(p)
        h.update(("%s\t%s\n" % (n, sha256_file(p))).encode("utf-8"))
    return len(names), total, checked_hex(h.hexdigest())


def retrieval_dirs(root):
    """Sorted list of (date, path) for <root>/retrieved=YYYY-MM-DD directories."""
    out = []
    if not os.path.isdir(root):
        return out
    for n in sorted(os.listdir(root)):
        m = re.match(r"^retrieved=([0-9]{4}-[0-9]{2}-[0-9]{2})$", n)
        if m and os.path.isdir(os.path.join(root, n)):
            out.append((m.group(1), os.path.join(root, n)))
    return out
