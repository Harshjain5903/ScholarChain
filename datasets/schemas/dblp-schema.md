# DBLP Schema Notes

Owner: dataset-engineer
Source: https://dblp.org/xml/
License: ODC-BY 1.0

## Record Types

DBLP XML contains these top-level record types:

| XML Element      | ScholarChain Mapping | Description                        |
|------------------|----------------------|------------------------------------|
| `<article>`      | Publication          | Journal article                    |
| `<inproceedings>`| Publication          | Conference/workshop paper          |
| `<proceedings>`  | Venue (collection)   | Conference proceedings volume      |
| `<book>`         | Publication          | Book or monograph                  |
| `<incollection>` | Publication          | Book chapter                       |
| `<phdthesis>`    | Publication          | Doctoral thesis                    |
| `<mastersthesis>`| Publication          | Master's thesis                    |
| `<www>`          | Author (homepage)    | Person or entity homepage record   |

## Key Fields

| DBLP Field    | Type         | ScholarChain Field           | Notes                              |
|---------------|--------------|------------------------------|------------------------------------|
| `key`         | string attr  | `source_id` (provenance)     | Unique DBLP key, e.g. "journals/tocs/SmithJ21" |
| `title`       | string       | `Publication.title`          | May contain LaTeX markup           |
| `author`      | string list  | `Author.name`                | Ordered author list; no unique IDs |
| `year`        | int          | `Publication.year`           | Publication year                   |
| `journal`     | string       | `Venue.name`                 | For articles only                  |
| `booktitle`   | string       | `Venue.name`                 | For inproceedings only             |
| `ee`          | string list  | `Publication.doi` (parsed)   | Electronic edition URL; DOI when prefix is doi.org |
| `url`         | string       | `Publication.dblp_url`       | DBLP record URL                    |
| `pages`       | string       | `Publication.pages`          | Page range, format varies          |
| `volume`      | string       | `Publication.volume`         | Journal volume                     |
| `number`      | string       | `Publication.issue`          | Journal issue number               |
| `crossref`    | string       | (internal join)              | Links inproceedings to proceedings |

## Linkage Fields and Join Keys

- **title**: Primary text-match key for cross-dataset linking. Requires normalization (lowercase, strip LaTeX, collapse whitespace).
- **author**: Author name strings. No unique identifiers. Must be matched via fuzzy/blocking strategies by entity-resolution-engineer.
- **year**: Exact numeric match for filtering and validation.
- **venue**: Journal name or booktitle. Requires normalization due to abbreviation variants.
- **DOI**: Extracted from `<ee>` elements containing "doi.org". Partial coverage (~60-70%).

## Domain Model Mapping

```
DBLP article/inproceedings -> Publication {
    source_id:   "dblp:" + key attribute
    title:       <title> (normalized)
    authors:     [<author>] -> list of Author.name
    year:        <year>
    venue:       <journal> or <booktitle> -> Venue.name
    doi:         parsed from <ee> if doi.org prefix present
    pages:       <pages>
    volume:      <volume>
    issue:       <number>
}

DBLP <www> records (key starting with "homepages/") -> Author {
    source_id:   "dblp:" + key attribute
    name:        <author>
    url:         <url>
}
```

## Known Quirks and Data Quality Issues

1. **LaTeX in titles**: Some titles contain LaTeX math markup (e.g., `$O(n \log n)$`). Must be stripped or converted during normalization.
2. **Author name variants**: The same person may appear as "John Smith", "J. Smith", "John A. Smith" across records. DBLP does some manual curation but inconsistencies remain.
3. **Missing DOIs**: Older records and some conference papers lack DOI links. The `<ee>` field may contain non-DOI URLs (publisher pages, arXiv links).
4. **Venue name inconsistency**: Conference names change over time (e.g., "SIGMOD Conference" vs "SIGMOD" vs "ACM SIGMOD"). Journal abbreviations vary.
5. **Encoding**: XML uses UTF-8 but some older records have encoding artifacts in author names (especially non-Latin scripts).
6. **DTD dependency**: The DBLP XML references an external DTD that defines entities. Parsing without the DTD may fail on entity references.
7. **Crossref links**: The `<crossref>` element links papers to their proceedings volume but is not universally present.
