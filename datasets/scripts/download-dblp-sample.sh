#!/usr/bin/env bash
# download-dblp-sample.sh
# Owner: dataset-engineer
# Provenance: ScholarChain dataset acquisition pipeline
#
# Downloads a small sample of the DBLP XML dump for local development and testing.
# This fetches only the first ~1 MB of the compressed file, not the full ~4 GB corpus.

set -euo pipefail

# --- Defaults ---
OUTPUT_DIR="datasets/samples"
FORCE=false
SAMPLE_BYTES=1048576  # 1 MB

# --- Usage ---
usage() {
    cat <<USAGE
Usage: $(basename "$0") [OPTIONS]

Download a small sample of the DBLP XML dataset for development and testing.

Options:
  --output-dir DIR   Output directory (default: datasets/samples/)
  --force            Re-download even if output already exists
  --help             Show this help message

What this does:
  1. Downloads the first ~1 MB of the DBLP gzip-compressed XML dump
  2. Extracts whatever XML records are complete within that slice
  3. Saves the raw partial download and a note about the sample

Source: https://dblp.org/xml/dblp.xml.gz
License: ODC-BY 1.0
USAGE
    exit 0
}

# --- Parse arguments ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        --output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --force)
            FORCE=true
            shift
            ;;
        --help)
            usage
            ;;
        *)
            echo "ERROR: Unknown option: $1" >&2
            usage
            ;;
    esac
done

# --- Paths ---
OUTPUT_FILE="${OUTPUT_DIR}/dblp-raw-sample.xml.gz"
EXTRACTED_FILE="${OUTPUT_DIR}/dblp-raw-sample-partial.xml"

# --- Idempotency check ---
if [[ -f "$OUTPUT_FILE" && "$FORCE" != "true" ]]; then
    echo "SKIP: $OUTPUT_FILE already exists. Use --force to re-download."
    exit 0
fi

# --- Create output directory ---
echo "STEP 1: Creating output directory ${OUTPUT_DIR}/"
mkdir -p "$OUTPUT_DIR"

# --- Download ---
echo "STEP 2: Downloading first ${SAMPLE_BYTES} bytes of DBLP XML dump..."
curl --fail --silent --show-error \
    --range "0-${SAMPLE_BYTES}" \
    --output "$OUTPUT_FILE" \
    --location \
    "https://dblp.org/xml/dblp.xml.gz"

# --- Validate download ---
echo "STEP 3: Validating download..."
if [[ ! -f "$OUTPUT_FILE" ]]; then
    echo "ERROR: Download failed -- output file does not exist." >&2
    exit 1
fi

FILE_SIZE=$(wc -c < "$OUTPUT_FILE" | tr -d ' ')
if [[ "$FILE_SIZE" -eq 0 ]]; then
    echo "ERROR: Downloaded file is empty." >&2
    rm -f "$OUTPUT_FILE"
    exit 1
fi

echo "  Downloaded ${FILE_SIZE} bytes to ${OUTPUT_FILE}"

# --- Extract partial XML (best-effort, gzip stream may be truncated) ---
echo "STEP 4: Attempting partial decompression (truncated gzip is expected)..."
# gzip -d will fail on truncated input, but may produce partial output
gzip -dc "$OUTPUT_FILE" > "$EXTRACTED_FILE" 2>/dev/null || true

if [[ -f "$EXTRACTED_FILE" && -s "$EXTRACTED_FILE" ]]; then
    EXTRACTED_SIZE=$(wc -c < "$EXTRACTED_FILE" | tr -d ' ')
    RECORD_COUNT=$(grep -c '<article\|<inproceedings\|<proceedings\|<book\|<phdthesis\|<mastersthesis' "$EXTRACTED_FILE" 2>/dev/null || echo "0")
    echo "  Extracted ${EXTRACTED_SIZE} bytes of partial XML"
    echo "  Found approximately ${RECORD_COUNT} record start tags"
else
    echo "  WARNING: Partial decompression produced no output."
    echo "  The raw compressed sample is still available at ${OUTPUT_FILE}"
fi

# --- Summary ---
echo ""
echo "DONE: DBLP sample download complete."
echo "  Raw compressed sample: ${OUTPUT_FILE}"
echo "  Partial XML extract:   ${EXTRACTED_FILE}"
echo "  Source: https://dblp.org/xml/dblp.xml.gz"
echo "  License: ODC-BY 1.0"
