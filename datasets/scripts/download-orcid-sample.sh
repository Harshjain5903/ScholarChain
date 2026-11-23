#!/usr/bin/env bash
# DEPRECATED: superseded by datasets/scripts/enrich_fetch.py orcid (bounded to orcid-candidates.txt; dry run by default). Do not use.
# download-orcid-sample.sh
# Owner: dataset-engineer
# Provenance: ScholarChain dataset acquisition pipeline
#
# Downloads a small sample of ORCID public data for local development and testing.
# Uses the ORCID public API to fetch a handful of researcher profiles.

set -euo pipefail

# --- Defaults ---
OUTPUT_DIR="datasets/samples"
FORCE=false

# --- Usage ---
usage() {
    cat <<USAGE
Usage: $(basename "$0") [OPTIONS]

Download a small sample of ORCID public researcher profiles for development
and testing.

Options:
  --output-dir DIR   Output directory (default: datasets/samples/)
  --force            Re-download even if output already exists
  --help             Show this help message

What this does:
  1. Queries the ORCID public API to search for researcher profiles
  2. Fetches summary data for a small batch of profiles
  3. Saves each response and a combined sample file

Source: https://pub.orcid.org/
License: CC0 (public data)

Note: The ORCID public API has rate limits. This script fetches only a small
number of records suitable for development.
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
SEARCH_FILE="${OUTPUT_DIR}/orcid-search-sample.json"
COMBINED_FILE="${OUTPUT_DIR}/orcid-profiles-sample.json"

# --- Idempotency check ---
if [[ -f "$COMBINED_FILE" && "$FORCE" != "true" ]]; then
    echo "SKIP: $COMBINED_FILE already exists. Use --force to re-download."
    exit 0
fi

# --- Create output directory ---
echo "STEP 1: Creating output directory ${OUTPUT_DIR}/"
mkdir -p "$OUTPUT_DIR"

# --- Search for ORCID profiles ---
echo "STEP 2: Searching ORCID public API for researcher profiles..."

# Search for profiles with the keyword "computer science" to get relevant records
SEARCH_URL="https://pub.orcid.org/v3.0/search/?q=keyword:computer+science&rows=10"

curl --fail --silent --show-error \
    --output "$SEARCH_FILE" \
    --location \
    --header "Accept: application/json" \
    "$SEARCH_URL"

# --- Validate search result ---
echo "STEP 3: Validating search response..."
if [[ ! -f "$SEARCH_FILE" || ! -s "$SEARCH_FILE" ]]; then
    echo "ERROR: Search request failed or returned empty response." >&2
    exit 1
fi

FILE_SIZE=$(wc -c < "$SEARCH_FILE" | tr -d ' ')
echo "  Downloaded ${FILE_SIZE} bytes of search results"

# --- Extract ORCIDs and fetch individual profiles ---
echo "STEP 4: Extracting ORCIDs from search results and fetching profiles..."

# Use python3 to parse search results and fetch profiles
python3 -I -c "
import json
import urllib.request
import sys

# Read search results
with open('${SEARCH_FILE}') as f:
    search_data = json.load(f)

# Extract ORCID identifiers
results = search_data.get('result', [])
if not results:
    print('WARNING: No results found in search response.', file=sys.stderr)
    sys.exit(0)

profiles = []
# Fetch up to 5 profiles
for entry in results[:5]:
    orcid_id = entry.get('orcid-identifier', {}).get('path', '')
    if not orcid_id:
        continue

    print(f'  Fetching profile: {orcid_id}')
    url = f'https://pub.orcid.org/v3.0/{orcid_id}/record'
    req = urllib.request.Request(url, headers={'Accept': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            profile = json.loads(resp.read().decode())
            profiles.append({
                'orcid': orcid_id,
                'name': profile.get('person', {}).get('name', {}),
                'activities_summary': {
                    'employments': profile.get('activities-summary', {}).get('employments', {}),
                    'works_count': len(
                        profile.get('activities-summary', {})
                        .get('works', {}).get('group', [])
                    )
                }
            })
    except Exception as e:
        print(f'  WARNING: Could not fetch {orcid_id}: {e}', file=sys.stderr)

# Save combined profiles
with open('${COMBINED_FILE}', 'w') as f:
    json.dump(profiles, f, indent=2, default=str)

print(f'  Saved {len(profiles)} profiles to ${COMBINED_FILE}')
" 2>&1 || {
    echo "WARNING: Profile fetching encountered errors. Partial results may be available."
}

# --- Validate combined output ---
echo "STEP 5: Validating combined output..."
if [[ -f "$COMBINED_FILE" && -s "$COMBINED_FILE" ]]; then
    FILE_SIZE=$(wc -c < "$COMBINED_FILE" | tr -d ' ')
    echo "  Combined file: ${FILE_SIZE} bytes"
    if python3 -I -c "import json; data = json.load(open('${COMBINED_FILE}')); print(f'  {len(data)} profiles in output')" 2>/dev/null; then
        echo "  Valid JSON confirmed."
    fi
else
    echo "WARNING: No combined output produced. Check API availability."
fi

# --- Summary ---
echo ""
echo "DONE: ORCID sample download complete."
echo "  Search results: ${SEARCH_FILE}"
echo "  Profiles:       ${COMBINED_FILE}"
echo "  Source: https://pub.orcid.org/"
echo "  License: CC0 (public data)"
