# OpenAlex Schema Notes

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
