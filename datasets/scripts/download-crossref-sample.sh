#!/usr/bin/env bash
# DEPRECATED: superseded by datasets/scripts/enrich_fetch.py crossref (bounded, resumable, redirect-following; dry run by default). Do not use.
# download-crossref-sample.sh
# Owner: dataset-engineer
# Provenance: ScholarChain dataset acquisition pipeline
#
# Downloads a small sample from the Crossref public metadata for local development
# and testing. Uses the Crossref REST API to fetch a small batch of records.

set -euo pipefail

# --- Defaults ---
OUTPUT_DIR="datasets/samples"
FORCE=false
SAMPLE_SIZE=50

# --- Usage ---
usage() {
    cat <<USAGE
Usage: $(basename "$0") [OPTIONS]

Download a small sample of Crossref metadata for development and testing.

Options:
  --output-dir DIR   Output directory (default: datasets/samples/)
  --force            Re-download even if output already exists
  --help             Show this help message

What this does:
  1. Queries the Crossref REST API for a small batch of journal article metadata
  2. Saves the response as JSON
  3. Validates the downloaded data

Source: https://api.crossref.org/
License: Public domain (metadata facts)

Note: This uses the polite API pool. For production use, register a contact
email with Crossref for the polite pool (via mailto parameter).
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
OUTPUT_FILE="${OUTPUT_DIR}/crossref-api-sample.json"

# --- Idempotency check ---
if [[ -f "$OUTPUT_FILE" && "$FORCE" != "true" ]]; then
    echo "SKIP: $OUTPUT_FILE already exists. Use --force to re-download."
    exit 0
fi

# --- Create output directory ---
echo "STEP 1: Creating output directory ${OUTPUT_DIR}/"
mkdir -p "$OUTPUT_DIR"

# --- Download via Crossref REST API ---
echo "STEP 2: Fetching ${SAMPLE_SIZE} journal article records from Crossref API..."

# Query for recent journal articles with references, to get rich metadata
API_URL="https://api.crossref.org/works?filter=type:journal-article,has-references:true&rows=${SAMPLE_SIZE}&sort=deposited&order=desc"

curl --fail --silent --show-error \
    --output "$OUTPUT_FILE" \
    --location \
    --header "Accept: application/json" \
    "$API_URL"

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

# --- Validate JSON structure ---
echo "STEP 4: Validating JSON format..."
if python3 -I -c "
import json, sys
with open('${OUTPUT_FILE}') as f:
    data = json.load(f)
msg = data.get('message', {})
total = msg.get('total-results', 'unknown')
items = len(msg.get('items', []))
print(f'  Total results available: {total}')
print(f'  Records in this sample: {items}')
" 2>/dev/null; then
    echo "  Valid JSON confirmed."
else
    echo "  WARNING: JSON validation failed or python3 not available."
fi

# --- Summary ---
echo ""
echo "DONE: Crossref sample download complete."
echo "  Sample file: ${OUTPUT_FILE}"
echo "  Source: https://api.crossref.org/"
echo "  License: Public domain (metadata)"
