#!/usr/bin/env bash
# DEPRECATED: superseded by datasets/scripts/enrich_fetch.py openalex (batched DOI filter, quota-aware, resumable; dry run by default). Do not use.
# download-openalex-sample.sh
# Owner: dataset-engineer
# Provenance: ScholarChain dataset acquisition pipeline
#
# Downloads a small sample from the OpenAlex snapshot for local development and testing.
# This fetches only the manifest and a single small partition, not the full ~300 GB snapshot.

set -euo pipefail

# --- Defaults ---
OUTPUT_DIR="datasets/samples"
FORCE=false
OPENALEX_S3_BASE="s3://openalex"

# --- Usage ---
usage() {
    cat <<USAGE
Usage: $(basename "$0") [OPTIONS]

Download a small sample of the OpenAlex dataset for development and testing.

Options:
  --output-dir DIR   Output directory (default: datasets/samples/)
  --force            Re-download even if output already exists
  --help             Show this help message

What this does:
  1. Downloads the OpenAlex works manifest to identify available partitions
  2. Fetches a single small partition file of work records
  3. Extracts the first 100 records as a JSON Lines sample

Source: https://docs.openalex.org/
License: CC0
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
MANIFEST_FILE="${OUTPUT_DIR}/openalex-works-manifest.txt"
SAMPLE_FILE="${OUTPUT_DIR}/openalex-raw-sample.jsonl.gz"
EXTRACTED_FILE="${OUTPUT_DIR}/openalex-raw-sample.jsonl"

# --- Idempotency check ---
if [[ -f "$EXTRACTED_FILE" && "$FORCE" != "true" ]]; then
    echo "SKIP: $EXTRACTED_FILE already exists. Use --force to re-download."
    exit 0
fi

# --- Create output directory ---
echo "STEP 1: Creating output directory ${OUTPUT_DIR}/"
mkdir -p "$OUTPUT_DIR"

# --- Check for AWS CLI ---
if ! command -v aws &>/dev/null; then
    echo "WARNING: AWS CLI not found. Attempting HTTPS fallback via OpenAlex API."
    echo ""
    echo "STEP 2: Fetching sample works from OpenAlex API (HTTPS fallback)..."

    # Use the OpenAlex API to fetch a small sample of works
    API_URL="https://api.openalex.org/works?per_page=50&sort=cited_by_count:desc"
    curl --fail --silent --show-error \
        --output "${OUTPUT_DIR}/openalex-api-sample.json" \
        --location \
        "$API_URL"

    if [[ -f "${OUTPUT_DIR}/openalex-api-sample.json" && -s "${OUTPUT_DIR}/openalex-api-sample.json" ]]; then
        FILE_SIZE=$(wc -c < "${OUTPUT_DIR}/openalex-api-sample.json" | tr -d ' ')
        echo "  Downloaded ${FILE_SIZE} bytes from OpenAlex API"
        echo ""
        echo "STEP 3: Validating JSON format..."
        if python3 -I -c "import json; json.load(open('${OUTPUT_DIR}/openalex-api-sample.json'))" 2>/dev/null; then
            echo "  Valid JSON confirmed."
        else
            echo "  WARNING: JSON validation failed. File may be incomplete."
        fi
    else
        echo "ERROR: API request failed or returned empty response." >&2
        exit 1
    fi

    echo ""
    echo "DONE: OpenAlex API sample download complete."
    echo "  API sample: ${OUTPUT_DIR}/openalex-api-sample.json"
    echo "  Source: https://api.openalex.org/"
    echo "  License: CC0"
    exit 0
fi

# --- Download via AWS S3 (no-sign-request for public bucket) ---
echo "STEP 2: Listing available works partitions from OpenAlex S3 bucket..."
aws s3 ls "${OPENALEX_S3_BASE}/data/works/" --no-sign-request \
    | head -5 > "$MANIFEST_FILE" 2>/dev/null || true

if [[ ! -s "$MANIFEST_FILE" ]]; then
    echo "WARNING: Could not list S3 bucket contents. Bucket structure may have changed."
    echo "  Refer to https://docs.openalex.org/ for current snapshot access."
    exit 1
fi

echo "  Available partition prefixes (first 5):"
cat "$MANIFEST_FILE" | sed 's/^/    /'

# Pick the first partition directory
FIRST_PARTITION=$(awk '{print $NF}' "$MANIFEST_FILE" | head -1)
echo ""
echo "STEP 3: Fetching first file from partition ${FIRST_PARTITION}..."

FIRST_FILE=$(aws s3 ls "${OPENALEX_S3_BASE}/data/works/${FIRST_PARTITION}" \
    --no-sign-request 2>/dev/null | head -1 | awk '{print $NF}')

if [[ -z "$FIRST_FILE" ]]; then
    echo "ERROR: Could not find any files in partition." >&2
    exit 1
fi

aws s3 cp "${OPENALEX_S3_BASE}/data/works/${FIRST_PARTITION}${FIRST_FILE}" \
    "$SAMPLE_FILE" --no-sign-request

# --- Extract first 100 records ---
echo "STEP 4: Extracting first 100 records..."
gzip -dc "$SAMPLE_FILE" | head -100 > "$EXTRACTED_FILE"

# --- Validate ---
echo "STEP 5: Validating sample..."
if [[ -f "$EXTRACTED_FILE" && -s "$EXTRACTED_FILE" ]]; then
    LINE_COUNT=$(wc -l < "$EXTRACTED_FILE" | tr -d ' ')
    echo "  Extracted ${LINE_COUNT} records to ${EXTRACTED_FILE}"
else
    echo "ERROR: Extraction produced no output." >&2
    exit 1
fi

echo ""
echo "DONE: OpenAlex sample download complete."
echo "  Manifest:  ${MANIFEST_FILE}"
echo "  Raw file:  ${SAMPLE_FILE}"
echo "  Sample:    ${EXTRACTED_FILE}"
echo "  Source: https://docs.openalex.org/"
echo "  License: CC0"
