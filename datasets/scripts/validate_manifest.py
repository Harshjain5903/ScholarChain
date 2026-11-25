#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""validate_manifest.py -- offline check of datasets/dataset-manifest.yaml against files on disk.

Owner: dataset-engineer (datasets/**)

No network access, standard library only (PyYAML is NOT required: a small
parser for the YAML subset used by the manifest is included and tested).

For every entry in `datasets:` it checks
  * required provenance keys: name, status, license, owning_agent and
    source_urls or documentation_url;
  * every item of the entry's `files:` list:
      kind: file  -> exists; size_bytes, sha256, lines (newline count) match
      kind: dir   -> exists; file_count, total_bytes and tree_sha256 (sha256
                     over sorted "<name>\\t<sha256>\\n" lines of the regular
                     files, optionally filtered by `suffix` / `prefix`) match
      kind: json  -> the file parses and the value at `json_pointer`
                     (RFC 6901) equals `expected`
    Absent items marked `tracked: false` (gitignored raw/derived data) are
    reported as SKIP, so a clean clone passes with a clear status; absent
    `tracked: true` items (committed files) FAIL.
  * for entries with `spec_check: true`: the course-specification fields
    (see SPEC_FIELDS: name+purpose, source+docs, licence/reuse, snapshot or
    retrieval date, retrieval command, format, expected AND observed size, S3
    raw prefix + upload status, integrity record, schema version, subset rule,
    incremental update strategy, linkage fields, personal-data AND privacy
    notes, known limitations, validation commands, owning agent) are present
    and non-empty. Presence only: values that need a human web check are
    flagged in the entry (human_confirmation_required), not judged here.

Output: one line per check (PASS | FAIL | SKIP), then a summary line.
Exit codes: 0 no FAIL (SKIPs allowed); 4 at least one FAIL; 2 manifest
unreadable or not in the expected shape; 1 other error.
"""

from __future__ import print_function

import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import enrich_common as ec  # noqa: E402

SCRIPT_NAME = "validate_manifest.py"
SCRIPT_VERSION = "1.0.0"
REQUIRED_KEYS = ("name", "status", "license", "owning_agent")
# (label, keys, mode): mode "all" = every key non-empty, "any" = at least one non-empty
SPEC_FIELDS = (
    ("name and purpose", ("name", "purpose"), "all"),
    ("official source and documentation links", ("source_urls", "documentation_url"), "any"),
    ("license or reuse terms", ("license", "reuse_terms", "license_evidence"), "any"),
    ("snapshot or retrieval date", ("snapshot_date", "retrieval_date", "planned_retrieval_date"), "any"),
    ("retrieval command or script", ("retrieval_command",), "all"),
    ("file format and compression", ("format", "compression"), "any"),
    ("expected AND observed size", ("expected_size", "observed_size"), "all"),
    ("S3 raw prefix and upload status", ("s3_raw_prefix", "s3_upload_status"), "all"),
    ("integrity record (sha256)", ("files",), "all"),
    ("schema version", ("schema_version",), "all"),
    ("selected subset rule", ("selected_subset_rule",), "all"),
    ("incremental update strategy", ("incremental_update_strategy",), "all"),
    ("linkage fields", ("linkage_fields",), "all"),
    ("personal-data and privacy notes", ("personal_data_notes", "privacy_notes"), "all"),
    ("known omissions or quality limitations", ("known_limitations",), "all"),
    ("validation commands", ("validation_commands",), "all"),
    ("owning agent", ("owning_agent",), "all"),
)


def spec_missing(entry):
    """Names of the specification fields that are absent or empty in a manifest entry."""
    missing = []
    for label, keys, mode in SPEC_FIELDS:
        have = [bool(entry.get(k)) for k in keys]
        ok = all(have) if mode == "all" else any(have)
        if ok and label.startswith("integrity"):
            ok = any(isinstance(i, dict) and (i.get("sha256") or i.get("tree_sha256")) for i in entry["files"])
        if not ok:
            missing.append(label)
    return missing


class ManifestError(Exception):
    pass


# ---------------------------------------------------------------------------
# Minimal YAML subset parser
# ---------------------------------------------------------------------------
# Supported: block maps and lists (incl. "- key: value" list items), plain,
# single- and double-quoted scalars (multi-line continuation folded with a
# space), block scalars (|, |-, >, >-), flow maps/lists of scalars, comments.
# Not supported (raises ManifestError): anchors, aliases, tags, documents.


def _strip_comment(line):
    out = []
    q = None
    prev = " "
    i = 0
    while i < len(line):
        ch = line[i]
        if q:
            out.append(ch)
            if q == '"' and ch == "\\" and i + 1 < len(line):
                out.append(line[i + 1])
                i += 2
                continue
            if ch == q:
                q = None
        else:
            if ch == "#" and prev in " \t":
                break
            if ch in "\"'" and (prev in " \t:-[{," or not out):
                q = ch
            out.append(ch)
        prev = ch
        i += 1
    return "".join(out).rstrip()


def _split_key(content):
    """Return (key, rest) if content is 'key: rest' / 'key:'; else None."""
    q = None
    i = 0
    if content[:1] in "\"'":
        q = content[0]
        j = content.find(q, 1)
        while q == '"' and j > 0 and content[j - 1] == "\\":
            j = content.find(q, j + 1)
        if j < 0:
            return None
        key = _scalar(content[:j + 1])
        rest = content[j + 1:]
        if rest.startswith(":") and (len(rest) == 1 or rest[1] in " \t"):
            return key, rest[1:].strip()
        return None
    for i, ch in enumerate(content):
        if ch == ":" and (i + 1 == len(content) or content[i + 1] in " \t"):
            key = content[:i].strip()
            if not key or key[0] in "[{":
                return None
            return key, content[i + 1:].strip()
    return None


_INT_RE = re.compile(r"^[-+]?[0-9]+$")
_FLOAT_RE = re.compile(r"^[-+]?([0-9]+\.[0-9]*|\.[0-9]+)([eE][-+]?[0-9]+)?$")


def _split_flow(s):
    parts, depth, q, cur = [], 0, None, []
    for ch in s:
        if q:
            cur.append(ch)
            if ch == q:
                q = None
            continue
        if ch in "\"'":
            q = ch
        elif ch in "[{":
            depth += 1
        elif ch in "]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    if "".join(cur).strip():
        parts.append("".join(cur).strip())
    return parts


def _scalar(s):
    s = s.strip()
    if s == "" or s in ("~", "null", "Null", "NULL"):
        return None
    if s[0] in "&*!":
        raise ManifestError("unsupported YAML feature (anchor/alias/tag): %r" % s[:40])
    if s[0] == '"':
        if not s.endswith('"') or len(s) < 2:
            raise ManifestError("unterminated double-quoted scalar: %r" % s[:60])
        try:
            return json.loads(s)
        except ValueError:
            return s[1:-1]
    if s[0] == "'":
        if not s.endswith("'") or len(s) < 2:
            raise ManifestError("unterminated single-quoted scalar: %r" % s[:60])
        return s[1:-1].replace("''", "'")
    if s[0] == "{" and s.endswith("}"):
        out = {}
        for p in _split_flow(s[1:-1]):
            kv = _split_key(p)
            if kv is None:
                raise ManifestError("bad flow map entry %r" % p)
            out[kv[0]] = _scalar(kv[1])
        return out
    if s[0] == "[" and s.endswith("]"):
        return [_scalar(p) for p in _split_flow(s[1:-1])]
    if s in ("true", "True", "TRUE"):
        return True
    if s in ("false", "False", "FALSE"):
        return False
    if _INT_RE.match(s):
        return int(s)
    if _FLOAT_RE.match(s):
        return float(s)
    return s


class _Parser(object):
    def __init__(self, text):
        self.raw = text.replace("\t", "    ").split("\n")
        self.lines = []  # (indent, content, raw_index)
        for idx, line in enumerate(self.raw):
            c = _strip_comment(line)
            if c.strip() in ("---", "..."):
                if idx == 0 or not any(l[1] for l in self.lines):
                    continue
                raise ManifestError("multiple YAML documents are not supported")
            if c.strip():
                self.lines.append([len(c) - len(c.lstrip(" ")), c.strip(), idx])

    def parse(self):
        if not self.lines:
            return None
        val, i = self.block(0, self.lines[0][0])
        if i != len(self.lines):
            raise ManifestError("unexpected content at line %d" % (self.lines[i][2] + 1))
        return val

    def block(self, i, indent):
        content = self.lines[i][1]
        if content == "-" or content.startswith("- "):
            return self.seq(i, indent)
        return self.map(i, indent)

    def continuation(self, i, indent, text):
        parts = [text]
        while i < len(self.lines) and self.lines[i][0] > indent:
            parts.append(self.lines[i][1])
            i += 1
        return " ".join(parts), i

    def block_scalar(self, i, indent, style):
        start_raw = self.lines[i - 1][2] + 1
        end_i = i
        while end_i < len(self.lines) and self.lines[end_i][0] > indent:
            end_i += 1
        end_raw = self.lines[end_i][2] if end_i < len(self.lines) else len(self.raw)
        body = self.raw[start_raw:end_raw]
        while body and not body[-1].strip():
            body.pop()
        ind = min((len(l) - len(l.lstrip(" ")) for l in body if l.strip()), default=0)
        body = [l[ind:] if l.strip() else "" for l in body]
        if style[0] == "|":
            text = "\n".join(body)
        else:
            out = []
            for l in body:
                if not l:
                    out.append("\n")
                elif out and not out[-1].endswith("\n"):
                    out.append(" " + l)
                else:
                    out.append(l)
            text = "".join(out)
        if not style.endswith("-"):
            text += "\n"
        return text, end_i

    def value_after_key(self, i, indent, rest):
        """i = index of the line after the key line."""
        if rest in ("|", "|-", ">", ">-", "|+", ">+"):
            return self.block_scalar(i, indent, rest.replace("+", ""))
        if rest == "":
            if i < len(self.lines) and self.lines[i][0] > indent:
                return self.block(i, self.lines[i][0])
            if i < len(self.lines) and self.lines[i][0] == indent and \
                    (self.lines[i][1] == "-" or self.lines[i][1].startswith("- ")):
                return self.seq(i, indent)
            return None, i
        text, j = self.continuation(i, indent, rest)
        return _scalar(text), j

    def map(self, i, indent):
        out = {}
        while i < len(self.lines) and self.lines[i][0] == indent:
            content = self.lines[i][1]
            if content == "-" or content.startswith("- "):
                break
            kv = _split_key(content)
            if kv is None:
                raise ManifestError("expected 'key: value' at line %d: %r" % (self.lines[i][2] + 1, content[:60]))
            key, rest = kv
            if key in out:
                raise ManifestError("duplicate key %r at line %d" % (key, self.lines[i][2] + 1))
            out[key], i = self.value_after_key(i + 1, indent, rest)
        if i < len(self.lines) and self.lines[i][0] > indent:
            raise ManifestError("bad indentation at line %d" % (self.lines[i][2] + 1))
        return out, i

    def seq(self, i, indent):
        out = []
        while i < len(self.lines) and self.lines[i][0] == indent:
            content = self.lines[i][1]
            if not (content == "-" or content.startswith("- ")):
                break
            item = content[1:].strip()
            if item == "":
                if i + 1 < len(self.lines) and self.lines[i + 1][0] > indent:
                    v, i = self.block(i + 1, self.lines[i + 1][0])
                else:
                    v, i = None, i + 1
                out.append(v)
                continue
            if _split_key(item) is not None and item[0] not in "[{":
                off = len(content) - len(content[1:].lstrip(" "))
                self.lines[i] = [indent + off, item, self.lines[i][2]]
                v, i = self.map(i, indent + off)
                out.append(v)
                continue
            text, i = self.continuation(i + 1, indent, item)
            out.append(_scalar(text))
        return out, i


def load_yaml_subset(text):
    return _Parser(text).parse()


# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------


def json_pointer(obj, ptr):
    if ptr == "":
        return obj
    if not ptr.startswith("/"):
        raise KeyError(ptr)
    for part in ptr[1:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        if isinstance(obj, list):
            obj = obj[int(part)]
        else:
            obj = obj[part]
    return obj


def count_lines(path):
    n = 0
    with open(path, "rb") as f:
        while True:
            b = f.read(1 << 20)
            if not b:
                break
            n += b.count(b"\n")
    return n


def check_item(root, item, cache):
    """Return list of (status, path, detail)."""
    if not isinstance(item, dict) or "path" not in item:
        return [("FAIL", "?", "files item without path")]
    rel = item["path"]
    kind = item.get("kind", "file")
    tracked = item.get("tracked", False) is True
    p = os.path.join(root, rel)
    exists = os.path.isdir(p) if kind == "dir" else os.path.isfile(p)
    if not exists:
        if tracked:
            return [("FAIL", rel, "committed (tracked) %s is missing" % kind)]
        return [("SKIP", rel, "absent locally; gitignored %s data (expected in a clean clone)" % kind)]
    res = []
    if kind == "file":
        if "size_bytes" in item:
            got = os.path.getsize(p)
            res.append(("PASS" if got == item["size_bytes"] else "FAIL", rel,
                        "size_bytes expected %s got %d" % (item["size_bytes"], got)))
        if "sha256" in item:
            got = cache.get(p) or ec.sha256_file(p)
            cache[p] = got
            res.append(("PASS" if got == item["sha256"] else "FAIL", rel,
                        "sha256 expected %s got %s" % (item["sha256"][:12], got[:12])))
        if "lines" in item:
            got = count_lines(p)
            res.append(("PASS" if got == item["lines"] else "FAIL", rel, "lines expected %s got %d" % (item["lines"], got)))
        if not res:
            res.append(("PASS", rel, "exists"))
    elif kind == "dir":
        n, total, digest = ec.tree_digest(p, item.get("suffix"), item.get("prefix"))
        for k, got in (("file_count", n), ("total_bytes", total), ("tree_sha256", digest)):
            if k in item:
                res.append(("PASS" if got == item[k] else "FAIL", rel,
                            "%s expected %s got %s" % (k, str(item[k])[:16], str(got)[:16])))
        if not res:
            res.append(("PASS", rel, "exists"))
    elif kind == "json":
        obj, err = ec.load_json_file(p)
        if err is not None:
            return [("FAIL", rel, err)]
        for chk in item.get("values") or []:
            ptr = chk.get("json_pointer", "")
            try:
                got = json_pointer(obj, ptr)
            except (KeyError, IndexError, ValueError, TypeError):
                res.append(("FAIL", rel, "json_pointer %s not found" % ptr))
                continue
            res.append(("PASS" if got == chk.get("expected") else "FAIL", rel,
                        "%s expected %r got %r" % (ptr, chk.get("expected"), got)))
    else:
        res.append(("FAIL", rel, "unknown kind %r" % kind))
    return res


def validate(manifest_path, root):
    with open(manifest_path, "r", encoding="utf-8") as f:
        text = f.read()
    try:
        doc = load_yaml_subset(text)
    except ManifestError as e:
        raise ManifestError("%s: %s" % (manifest_path, e))
    if not isinstance(doc, dict) or not isinstance(doc.get("datasets"), list):
        raise ManifestError("%s: top level must be a map with a 'datasets' list" % manifest_path)
    results = []
    cache = {}
    names = set()
    for ds_entry in doc["datasets"]:
        name = ds_entry.get("name", "?") if isinstance(ds_entry, dict) else "?"
        if not isinstance(ds_entry, dict):
            results.append(("FAIL", name, "-", "entry is not a map"))
            continue
        if name in names:
            results.append(("FAIL", name, "-", "duplicate dataset name"))
        names.add(name)
        missing = [k for k in REQUIRED_KEYS if not ds_entry.get(k)]
        if not (ds_entry.get("source_urls") or ds_entry.get("documentation_url")):
            missing.append("source_urls|documentation_url")
        results.append(("FAIL" if missing else "PASS", name, "-",
                        ("missing required keys: %s" % ", ".join(missing)) if missing else "required provenance/licence keys present"))
        if ds_entry.get("spec_check") is True:
            sm = spec_missing(ds_entry)
            results.append(("FAIL" if sm else "PASS", name, "-",
                            ("missing specification fields: %s" % "; ".join(sm)) if sm
                            else "all %d course-specification fields present" % len(SPEC_FIELDS)))
        for item in ds_entry.get("files") or []:
            for st, rel, detail in check_item(root, item, cache):
                results.append((st, name, rel, detail))
    return results


def run(argv):
    ap = argparse.ArgumentParser(prog=SCRIPT_NAME, description=__doc__.split("\n")[0])
    ap.add_argument("--manifest", default="datasets/dataset-manifest.yaml")
    ap.add_argument("--root", default=None, help="directory manifest paths are relative to (default: git root or cwd)")
    args = ap.parse_args(argv)
    root = args.root or ec.ds.repo_root() or os.getcwd()
    ec.set_display_root(root)
    try:
        results = validate(args.manifest, root)
    except (ManifestError, OSError) as e:
        sys.stderr.write("ERROR: %s\n" % e)
        return 2
    counts = {"PASS": 0, "FAIL": 0, "SKIP": 0}
    for st, name, rel, detail in results:
        counts[st] += 1
        print("%-4s %-9s %s  %s" % (st, name, rel, detail))
    overall = "FAIL" if counts["FAIL"] else ("PASS-WITH-SKIPS" if counts["SKIP"] else "PASS")
    print("SUMMARY: status=%s pass=%d fail=%d skip=%d" % (overall, counts["PASS"], counts["FAIL"], counts["SKIP"]))
    return 4 if counts["FAIL"] else 0


def main(argv=None):
    try:
        return run(sys.argv[1:] if argv is None else argv)
    except Exception as e:  # pragma: no cover
        sys.stderr.write("ERROR: %s\n" % e)
        return 1


if __name__ == "__main__":
    sys.exit(main())
