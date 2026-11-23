#!/usr/bin/env bash
# download-dblp.sh
# Owner: dataset-engineer
# Provenance: ScholarChain dataset acquisition pipeline (remediation Phase 03)
#
# Downloads the FULL DBLP XML dump (dblp.xml.gz, about 1.1 GB) and dblp.dtd
# from https://dblp.org/xml/ into datasets/raw/dblp/snapshot=<date>/ and
# writes SHA256SUMS. datasets/raw/ is gitignored; nothing here is committed.
#
# Safety:
#   * never downloads unless --i-understand-full-download is given;
#   * skips (no network) when --snapshot-date is given and that snapshot's
#     SHA256SUMS already verifies;
#   * refuses to replace an existing snapshot directory unless --force.
# The snapshot date defaults to the gzip header mtime (UTC) of the download.
# Licence: dblp metadata is CC0 1.0 (see the comment header of dblp.dtd).

set -euo pipefail

BASE_URL="https://dblp.org/xml"
RAW_ROOT="datasets/raw/dblp"
SNAPSHOT_DATE=""
CONFIRM=false
FORCE=false

usage() {
    cat <<USAGE
Usage: $(basename "$0") --i-understand-full-download [--snapshot-date YYYY-MM-DD] [--raw-root DIR] [--force]

Download the full DBLP dump (about 1.1 GB) and its DTD from ${BASE_URL}/.

Options:
  --i-understand-full-download  Required to perform any download.
  --snapshot-date YYYY-MM-DD    Target snapshot (default: gzip header mtime, UTC).
                                With an existing, verifying SHA256SUMS this skips.
  --raw-root DIR                Root directory (default: ${RAW_ROOT})
  --force                       Replace an existing snapshot directory.
  --help                        Show this help.

Output: <raw-root>/snapshot=<date>/{dblp.xml.gz,dblp.dtd,SHA256SUMS,SOURCE.txt}
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --i-understand-full-download) CONFIRM=true; shift ;;
        --snapshot-date) SNAPSHOT_DATE="${2:?--snapshot-date needs a value}"; shift 2 ;;
        --raw-root) RAW_ROOT="${2:?--raw-root needs a value}"; shift 2 ;;
        --force) FORCE=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) echo "ERROR: unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

if command -v sha256sum >/dev/null 2>&1; then
    SHA_CMD=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
    SHA_CMD=(shasum -a 256)
else
    echo "ERROR: need sha256sum or shasum" >&2
    exit 1
fi

verify_dir() {
    # $1 = snapshot directory; succeeds only if SHA256SUMS covers both files and verifies
    local dir="$1"
    [[ -f "${dir}/SHA256SUMS" && -f "${dir}/dblp.xml.gz" && -f "${dir}/dblp.dtd" ]] || return 1
    grep -q ' dblp.xml.gz$' "${dir}/SHA256SUMS" && grep -q ' dblp.dtd$' "${dir}/SHA256SUMS" || return 1
    (cd "$dir" && "${SHA_CMD[@]}" -c SHA256SUMS >/dev/null 2>&1)
}

if [[ -n "$SNAPSHOT_DATE" ]]; then
    if [[ ! "$SNAPSHOT_DATE" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
        echo "ERROR: --snapshot-date must be YYYY-MM-DD" >&2
        exit 2
    fi
    TARGET="${RAW_ROOT}/snapshot=${SNAPSHOT_DATE}"
    if verify_dir "$TARGET"; then
        echo "SKIP: ${TARGET} already present and SHA256SUMS verifies; no download."
        exit 0
    fi
fi

if [[ "$CONFIRM" != "true" ]]; then
    echo "REFUSED: this downloads the full DBLP dump (about 1.1 GB)." >&2
    echo "Re-run with --i-understand-full-download to proceed." >&2
    exit 2
fi

STAGING="${RAW_ROOT}/.staging"
rm -rf "$STAGING"
mkdir -p "$STAGING"
trap 'rm -rf "$STAGING"' EXIT

DOWNLOADED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "STEP 1: downloading ${BASE_URL}/dblp.dtd"
curl --fail --silent --show-error --location --output "${STAGING}/dblp.dtd" "${BASE_URL}/dblp.dtd"
echo "STEP 2: downloading ${BASE_URL}/dblp.xml.gz (about 1.1 GB)"
curl --fail --show-error --location --output "${STAGING}/dblp.xml.gz" "${BASE_URL}/dblp.xml.gz"

echo "STEP 3: checking gzip integrity"
gzip -t "${STAGING}/dblp.xml.gz"

if [[ -z "$SNAPSHOT_DATE" ]]; then
    # gzip header bytes 4..7 = mtime, little-endian uint32
    MTIME=$(od -An -tu1 -j4 -N4 "${STAGING}/dblp.xml.gz" | awk '{print $1 + 256*$2 + 65536*$3 + 16777216*$4}')
    if [[ -z "$MTIME" || "$MTIME" -eq 0 ]]; then
        echo "ERROR: gzip header has no mtime; pass --snapshot-date" >&2
        exit 1
    fi
    SNAPSHOT_DATE=$(date -u -r "$MTIME" +%Y-%m-%d 2>/dev/null || date -u -d "@${MTIME}" +%Y-%m-%d)
fi
TARGET="${RAW_ROOT}/snapshot=${SNAPSHOT_DATE}"

echo "STEP 4: writing SHA256SUMS"
(cd "$STAGING" && "${SHA_CMD[@]}" dblp.xml.gz dblp.dtd > SHA256SUMS)
cat > "${STAGING}/SOURCE.txt" <<INFO
source_xml=${BASE_URL}/dblp.xml.gz
source_dtd=${BASE_URL}/dblp.dtd
downloaded_at_utc=${DOWNLOADED_AT}
snapshot_date=${SNAPSHOT_DATE}
licence=CC0 1.0 (dblp.dtd comment header)
script=datasets/scripts/download-dblp.sh
INFO

if [[ -d "$TARGET" ]]; then
    if verify_dir "$TARGET" && cmp -s "${TARGET}/SHA256SUMS" "${STAGING}/SHA256SUMS"; then
        echo "SKIP: ${TARGET} already holds identical files."
        exit 0
    fi
    if [[ "$FORCE" != "true" ]]; then
        echo "ERROR: ${TARGET} exists with different content; use --force to replace." >&2
        exit 2
    fi
    rm -rf "$TARGET"
fi
mkdir -p "$(dirname "$TARGET")"
mv "$STAGING" "$TARGET"
trap - EXIT

echo "STEP 5: verifying"
(cd "$TARGET" && "${SHA_CMD[@]}" -c SHA256SUMS)
echo "DONE: ${TARGET}"
cat "${TARGET}/SHA256SUMS"
