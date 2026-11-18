# ORCID Schema Notes

Owner: dataset-engineer
Source: https://info.orcid.org/documentation/
License: CC0 (public data)

## Record Types

The ORCID public data dump contains one record per ORCID iD, with nested sections:

| Section             | ScholarChain Mapping      | Description                            |
|---------------------|---------------------------|----------------------------------------|
| Person              | Author                    | Name, biography, keywords              |
| Activities: Works   | Publication (references)  | Self-reported publication list          |
| Activities: Employments | Author.affiliations   | Employment history                     |
| Activities: Educations  | Author.education      | Education history                      |
| Activities: Fundings    | (not yet modeled)     | Grant and funding records              |

## Key Fields -- Person Section

| ORCID Field                   | Type         | ScholarChain Field           | Notes                              |
|-------------------------------|--------------|------------------------------|------------------------------------|
| `orcid-identifier.path`       | string       | `Author.orcid`               | The ORCID iD, e.g. "0000-0002-1234-5678" |
| `person.name.given-names`     | string       | `Author.given_name`          | First/given name                   |
| `person.name.family-name`     | string       | `Author.family_name`         | Last/family name                   |
| `person.name.credit-name`     | string       | `Author.name`                | Preferred publication name         |
| `person.other-names`          | string list  | `Author.name_variants`       | Alternative name spellings         |
| `person.keywords`             | string list  | `Author.research_interests`  | Self-reported keywords             |
| `person.researcher-urls`      | object list  | `Author.urls`                | Homepage, lab page, etc.           |
| `person.external-identifiers` | object list  | `Author.external_ids`        | Scopus ID, ResearcherID, etc.      |

## Key Fields -- Works Section

| ORCID Field                        | Type         | ScholarChain Field           | Notes                              |
|------------------------------------|--------------|------------------------------|------------------------------------|
| `work-summary.title`               | string       | (cross-reference)            | Publication title                  |
| `work-summary.external-ids`        | object list  | `Publication.doi` etc.       | DOI, PMID, arXiv ID, etc.         |
| `work-summary.publication-date`    | object       | `Publication.year`           | May be partial (year only)         |
| `work-summary.journal-title`       | string       | `Venue.name`                 | Journal or conference name         |
| `work-summary.type`                | string       | `Publication.type`           | "JOURNAL_ARTICLE", "CONFERENCE_PAPER", etc. |
| `work-summary.put-code`            | int          | (internal)                   | ORCID internal record ID           |

## Key Fields -- Employments Section

| ORCID Field                          | Type         | ScholarChain Field           | Notes                              |
|--------------------------------------|--------------|------------------------------|------------------------------------|
| `employment-summary.organization`    | object       | `Author.affiliation`         | Employer name and identifiers      |
| `employment-summary.role-title`      | string       | `Author.role`                | Job title                          |
| `employment-summary.department-name` | string       | `Author.department`          | Department name                    |
| `employment-summary.start-date`      | object       | `Author.affiliation_start`   | Start date of employment           |
| `employment-summary.end-date`        | object       | `Author.affiliation_end`     | End date (null if current)         |

## Linkage Fields and Join Keys

- **ORCID**: The primary unique identifier. Format: 0000-XXXX-XXXX-XXXX. Used to link author records across all datasets.
- **author (name)**: Credit name or given+family name. Fuzzy matching needed due to name variants.
- **DOI**: From the works section's external identifiers. Enables linking ORCID profiles to Publication records.

## Domain Model Mapping

```
ORCID Record -> Author {
    source_id:          "orcid:" + orcid-identifier.path
    orcid:              orcid-identifier.path
    name:               person.name.credit-name or given-names + " " + family-name
    given_name:         person.name.given-names
    family_name:        person.name.family-name
    name_variants:      person.other-names
    affiliations:       activities.employments[].organization.name
    research_interests: person.keywords
}

ORCID Work -> (cross-reference to Publication) {
    doi:    work-summary.external-ids where type="doi"
    title:  work-summary.title
    year:   work-summary.publication-date.year
}
```

## Known Quirks and Data Quality Issues

1. **Self-reported data**: All ORCID data is entered or claimed by the researcher. Quality varies widely. Some profiles are minimal (name only), others are comprehensive.
2. **Visibility controls**: Researchers can mark fields as public, limited, or private. The public dump includes only public-visibility fields. A profile may exist but appear nearly empty.
3. **Name encoding**: International names may have transliteration issues. The credit-name field is the researcher's preferred form but is not always populated.
4. **Duplicate profiles**: Some researchers have multiple ORCID iDs. ORCID provides no built-in deduplication.
5. **Work list completeness**: The works section depends on researcher curation or publisher auto-updates. Many profiles have incomplete publication lists.
6. **Affiliation strings**: Organization names are free text. "MIT", "Massachusetts Institute of Technology", and "M.I.T." are all possible. The organization may include a ROR or GRID identifier for disambiguation.
7. **Date precision**: Dates may be year-only, year-month, or full dates. Some are null.
8. **Coverage bias**: ORCID adoption is higher in biomedical sciences, Europe, and among early-career researchers. Coverage in computer science is growing but uneven.
