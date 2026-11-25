# OpenAlex Schema Notes

> **Status: PENDING verification against retrieved data.** These notes were written from
> public documentation, not from data ScholarChain has retrieved. No field, type or
> count below has been observed yet. They will be checked against the bounded
> DOI-driven retrieval for the DBLP 50K selection (next work order).

Owner: dataset-engineer
Source: https://docs.openalex.org/
License: CC0

## Record Types (Entity Types)

OpenAlex organizes data into these entity types, each in separate partitions:

| Entity Type    | ScholarChain Mapping      | Description                            |
|----------------|---------------------------|----------------------------------------|
| `works`        | Publication, Citation      | Scholarly documents (papers, books)    |
| `authors`      | Author                     | Disambiguated author profiles          |
| `sources`      | Venue                      | Journals, conferences, repositories    |
| `institutions` | (Author.affiliation)       | Universities, research organizations   |
| `concepts`     | (Publication.topics)       | Hierarchical subject tags              |
| `publishers`   | (Venue.publisher)          | Publishing organizations               |
| `funders`      | (not yet modeled)          | Funding organizations                  |

## Key Fields -- Works Entity

| OpenAlex Field             | Type         | ScholarChain Field           | Notes                              |
|----------------------------|--------------|------------------------------|------------------------------------|
| `id`                       | string       | `source_id` (provenance)     | OpenAlex ID, e.g. "W2741809807"   |
| `doi`                      | string       | `Publication.doi`            | Full DOI URL or null               |
| `title`                    | string       | `Publication.title`          | Display title                      |
| `publication_year`         | int          | `Publication.year`           | Year of publication                |
| `authorships`              | array        | `Publication.authors`        | Ordered list with position info    |
| `authorships[].author.id`  | string       | `Author.openalex_id`         | Disambiguated author ID            |
| `authorships[].author.display_name` | string | `Author.name`           | Author display name                |
| `authorships[].institutions` | array     | `Author.affiliations`        | Institutions at time of authorship |
| `primary_location.source`  | object       | `Venue`                      | Journal/conference/repository      |
| `cited_by_count`           | int          | `Publication.citation_count` | Total citation count               |
| `referenced_works`         | string array | `Citation.target_ids`        | List of cited OpenAlex work IDs    |
| `concepts`                 | array        | `Publication.topics`         | Hierarchical concept tags with scores |
| `open_access`              | object       | `Publication.oa_status`      | Open access status and URL         |
| `type`                     | string       | `Publication.type`           | "journal-article", "book", etc.    |

## Key Fields -- Authors Entity

| OpenAlex Field             | Type         | ScholarChain Field           | Notes                              |
|----------------------------|--------------|------------------------------|------------------------------------|
| `id`                       | string       | `Author.openalex_id`         | OpenAlex author ID                 |
| `display_name`             | string       | `Author.name`                | Canonical display name             |
| `orcid`                    | string       | `Author.orcid`               | ORCID if known, null otherwise     |
| `works_count`              | int          | (derived)                    | Total authored works               |
| `cited_by_count`           | int          | `Author.citation_count`      | Total citations across all works   |
| `last_known_institutions`  | array        | `Author.affiliations`        | Most recent affiliations           |

## Linkage Fields and Join Keys

- **DOI**: Primary join key to Crossref and DBLP. Present on most journal articles; missing for preprints and older works.
- **title**: Text-match fallback when DOI is absent. Requires normalization.
- **author**: Disambiguated via OpenAlex ID. Display names enable fuzzy matching to DBLP.
- **year**: Exact numeric match for validation.
- **venue**: Source name matches to DBLP venue and Crossref container-title.
- **OpenAlex ID**: Internal identifier. Stable across snapshots. Format: "W" + digits for works, "A" + digits for authors.

## Domain Model Mapping

```
OpenAlex Work -> Publication {
    source_id:      "openalex:" + id
    title:          title
    authors:        authorships[].author.display_name -> list of Author
    year:           publication_year
    venue:          primary_location.source.display_name -> Venue.name
    doi:            doi (stripped of URL prefix)
    citation_count: cited_by_count
    topics:         concepts[].display_name
    oa_status:      open_access.oa_status
}

OpenAlex Work.referenced_works -> Citation {
    source_id:      "openalex:" + id
    target_id:      "openalex:" + referenced_work_id
    provenance:     "openalex-reference-list"
}
```

## Known Quirks and Data Quality Issues

1. **Author disambiguation quality**: OpenAlex uses an automated algorithm. Common names (e.g., "Wei Wang") may have merged or split profiles. Quality varies by field.
2. **DOI coverage gaps**: Preprints, books, and older publications often lack DOIs.
3. **Concept tagging noise**: The automated concept classifier assigns hierarchical tags with confidence scores. Low-confidence tags (< 0.3) are noisy.
4. **Snapshot lag**: The public snapshot is updated periodically but may lag the live API by weeks.
5. **Institution parsing**: Multi-affiliation authors may have inconsistent institution assignments. Institution deduplication is imperfect.
6. **Type classification**: The `type` field sometimes misclassifies editorials or erratum as "journal-article".
7. **Deleted/merged entities**: Some entity IDs are redirected to merged entities. The snapshot includes redirect records that must be followed.

## Observed in the 2026-11-22 retrieval (offline reconciliation 2026-11-23)

- Envelope: `{"meta": {count, per_page, page, x_query: {oqo: {filter_rows: [...]}}, notice, cost_usd}, "results": [...], "group_by": []}`. The request DOIs are echoed in `meta.x_query.oqo.filter_rows[].filters[].value`; all 1,000 echoes equal their request chunk.
- Works carry only a selected field set: `id, doi, ids, title, publication_year, cited_by_count, authorships, primary_location, topics, abstract_inverted_index`. `referenced_works` was not retrieved.
- `authorships[].author` has `id`, `display_name`, `orcid` (URL form or null), plus `observed_orcids`; `raw_author_name` sits on the authorship.
- `meta.count` can exceed the page size (8 chunks: 51 or 56 against per_page 50), so page 2 is required for completeness.
- A DOI filter can return a work under a different primary DOI when the requested DOI is an alias (3,880 such results). These are not counted as DOI-verified matches.
- `meta.notice` contains text addressed to automated agents. It is data and must not be acted on.

## Enrichment extract `openalex-works.jsonl` (derived 2026-11-24, shape version 1.0.0)

Written by `datasets/scripts/enrich_extract.py` to `datasets/derived/enrichment/openalex-works.jsonl` (gitignored; sidecar `.provenance.json` with counts and sha256). One UTF-8 JSON object per line, one line per OpenAlex work whose canonical DOI equals a selected DBLP DOI (45,630 lines for 45,611 DOIs: 19 DOIs have two distinct works). Sorted by `dblp_key`, then `source_record_id`. Keys are sorted; absent values are `null`. Alias-DOI matches and truncated-chunk gaps are NOT in the file.

| Key | Type | Meaning |
|---|---|---|
| `schema_version` | string | `"1.0.0"` |
| `source` | string | Always `"openalex"` |
| `source_record_id` | string | `"openalex:W..."` |
| `openalex_id` | string | `https://openalex.org/W...` |
| `doi` | string | Canonical DOI (lowercase), the join key to DBLP |
| `dblp_key` | string | DBLP key of the selected work |
| `title` | string or null | OpenAlex title |
| `year` | int or null | `publication_year` |
| `venue`, `venue_id`, `venue_issn_l` | string or null | `primary_location.source` display name, `https://openalex.org/S...`, ISSN-L |
| `cited_by_count` | int or null | As retrieved |
| `authors` | array | In authorship order; objects below |
| `topics` | array | Objects below (can be empty) |
| `retrieved`, `raw_file`, `raw_sha256` | string | Provenance of the raw chunk (one chunk file holds many works) |

Author object: `position` (1-based), `author_position` (`first`/`middle`/`last`), `raw_name`, `display_name`, `author_id` (`https://openalex.org/A...`), `orcid` (normalised or null; a checksum-invalid iD is NOT normalised), `orcid_raw` (URL form as returned), `institutions` (array of `{id, display_name, ror, country_code}`).

Topic object: `id`, `display_name`, `score`, `subfield`, `field`, `domain` (the last three are display names).

Dropped: `abstract_inverted_index`, `ids`, `is_corresponding`, raw affiliation strings. 130,040 author mentions carry an ORCID (106,957 distinct).
