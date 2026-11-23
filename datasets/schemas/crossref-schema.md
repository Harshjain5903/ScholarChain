# Crossref Schema Notes

> **Status: PENDING verification against retrieved data.** These notes were written from
> public documentation, not from data ScholarChain has retrieved. No field, type or
> count below has been observed yet. They will be checked against the bounded
> DOI-driven retrieval for the DBLP 50K selection (next work order).

Owner: dataset-engineer
Source: https://www.crossref.org/documentation/
License: Public domain (metadata facts)

## Record Types

Crossref metadata is organized by content type, registered via DOI:

| Crossref Type          | ScholarChain Mapping      | Description                            |
|------------------------|---------------------------|----------------------------------------|
| `journal-article`      | Publication               | Journal article (most common)          |
| `proceedings-article`  | Publication               | Conference paper                       |
| `book-chapter`         | Publication               | Chapter in an edited volume            |
| `book`                 | Publication               | Monograph or edited volume             |
| `dissertation`         | Publication               | Thesis or dissertation                 |
| `posted-content`       | Publication (preprint)    | Preprints (e.g., from bioRxiv)         |
| `reference-entry`      | (not modeled)             | Encyclopedia/dictionary entries         |

## Key Fields

| Crossref Field          | Type         | ScholarChain Field           | Notes                              |
|-------------------------|--------------|------------------------------|------------------------------------|
| `DOI`                   | string       | `Publication.doi`            | The DOI (without URL prefix)       |
| `title`                 | string array | `Publication.title`          | Usually single-element array       |
| `author`                | object array | `Publication.authors`        | Author list with name parts        |
| `author[].given`        | string       | `Author.given_name`          | Given/first name                   |
| `author[].family`       | string       | `Author.family_name`         | Family/last name                   |
| `author[].ORCID`        | string       | `Author.orcid`               | ORCID URL if deposited by publisher|
| `author[].affiliation`  | object array | `Author.affiliations`        | Affiliation name strings           |
| `published-print`       | object       | `Publication.year`           | Print publication date             |
| `published-online`      | object       | `Publication.year`           | Online publication date            |
| `container-title`       | string array | `Venue.name`                 | Journal or conference name         |
| `ISSN`                  | string array | `Venue.issn`                 | Print and electronic ISSNs         |
| `type`                  | string       | `Publication.type`           | Content type                       |
| `reference`             | object array | `Citation.targets`           | List of cited works                |
| `reference[].DOI`       | string       | `Citation.target_doi`        | DOI of cited work (when available) |
| `reference[].unstructured` | string    | `Citation.raw_string`        | Unstructured citation string       |
| `is-referenced-by-count`| int          | `Publication.citation_count` | Times cited                        |
| `publisher`             | string       | `Venue.publisher`            | Publisher name                     |
| `subject`               | string array | `Publication.subjects`       | Subject categories                 |
| `abstract`              | string       | (not ingested)               | JATS XML abstract; publisher-restricted |

## Linkage Fields and Join Keys

- **DOI**: The primary join key. Every Crossref record has a DOI by definition. Used to link to DBLP (via parsed `<ee>` URLs), OpenAlex, and ORCID works.
- **title**: Text-match fallback. Stored as an array; use first element. Requires normalization.
- **author**: Structured name parts (given/family). Better structured than DBLP but still requires fuzzy matching for cross-dataset linking.
- **year**: Extracted from `published-print` or `published-online` date-parts. Prefer print date, fall back to online date.
- **venue**: `container-title` maps to journal/conference name. Usually includes both full name and abbreviation.
- **ISSN**: Reliable venue identifier for journal matching. Crossref typically provides both print and electronic ISSNs.

## Domain Model Mapping

```
Crossref Work -> Publication {
    source_id:      "crossref:" + DOI
    doi:            DOI
    title:          title[0]
    authors:        author[].{given + " " + family} -> list of Author
    year:           published-print.date-parts[0][0] or published-online.date-parts[0][0]
    venue:          container-title[0] -> Venue.name
    issn:           ISSN[] -> Venue.issn
    citation_count: is-referenced-by-count
    type:           type
    publisher:      publisher
}

Crossref Work.reference[] -> Citation {
    source_doi:     DOI (the citing work)
    target_doi:     reference[].DOI (when present)
    raw_string:     reference[].unstructured (when DOI absent)
    provenance:     "crossref-reference-list"
}
```

## Known Quirks and Data Quality Issues

1. **Reference list completeness**: Not all publishers deposit reference lists. Coverage has improved since Crossref's Initiative for Open Citations (I4OC) but older records often lack references entirely.
2. **Author name formats**: Some publishers deposit only initials for given names. Format consistency varies by publisher.
3. **ORCID in author records**: Only present when the publisher deposits it during DOI registration. Coverage is growing but far from universal.
4. **Affiliation data**: Sparse. Many publishers do not deposit affiliation metadata. When present, it is free-text with no normalization.
5. **Title as array**: The `title` field is always an array, typically with one element. Some records have subtitle as a separate field.
6. **Date ambiguity**: Some records have only `published-online` and no `published-print`. Date-parts can be incomplete (year only, or year-month).
7. **Abstract restrictions**: Abstracts, when present, are in JATS XML format and may carry publisher licensing restrictions. ScholarChain does not ingest abstracts from Crossref.
8. **Unstructured references**: When a cited work lacks a DOI, the reference is an unstructured text string requiring parsing (by entity-resolution-engineer) to extract title, authors, and year.
9. **Duplicate DOIs**: Rare but possible. Some DOIs have been registered multiple times with slightly different metadata.

## Observed in the 2026-11-22 retrieval (offline reconciliation 2026-11-23)

- Envelope: `{"status": "ok", "message-type": "work", "message-version": "1.0.0", "message": {...}}`; one file per requested DOI, named `sha256(requested DOI).json`.
- All 42,910 saved responses parse, have `status: ok` and a `message.DOI` that canonicalises (trim, lowercase) to the requested DOI.
- `message.author[]` carries `given`, `family`, `sequence`, optional `ORCID` (URL form `https://orcid.org/...` or `http://orcid.org/...`) and `authenticated-orcid`; 10,315 records have at least one author ORCID.
- `message.reference[]` is present in 38,718 records (1,168,771 entries).
- Requests for alias DOIs answer HTTP 301 to the primary DOI; follow redirects and expect `message.DOI` to differ from the request.
- Counts, rejects and coverage: `datasets/dataset-manifest.yaml` (crossref) and `datasets/derived/enrichment/reconcile-summary.json`.
