# DBLP Schema Notes

Owner: dataset-engineer
Source: https://dblp.org/xml/ (`dblp.xml.gz`, `dblp.dtd`)
Snapshot: `datasets/raw/dblp/snapshot=2026-11-16/` (gzip header mtime 2026-11-16T22:34:13Z,
newest record `mdate` observed 2026-11-16; downloaded 2026-11-17)
License: CC0 1.0. Quoted from the comment header of `dblp.dtd`: "The metadata provided by dblp on
its webpages, as well as their XML, JSON, RDF, RIS, BibTeX, and text export formats available at our
website, is released under the CC0 1.0 Public Domain Dedication license."

Everything below was **observed** in this snapshot by `datasets/scripts/dblp_select.py` (whole
dump), or by profiling `datasets/derived/dblp/selection-50k.xml` (50,000 selected records). The
profiling script was a one-off and is not committed. Fields of the other record types were not
profiled. Selected record bytes are not modified.

## Encoding and entities

- XML declaration: `<?xml version="1.0" encoding="ISO-8859-1"?>`, then
  `<!DOCTYPE dblp SYSTEM "dblp.dtd">`, root `<dblp>`.
- Non-ASCII characters appear as named entities declared in `dblp.dtd` (65 general
  `<!ENTITY>` declarations, e.g. `&eacute;`, `&ouml;`; the most frequent in the 50K extract were `&eacute;`,
  `&aacute;`, `&iacute;`, `&ouml;`, `&uuml;`). The 50K extract has no raw bytes above 0x7F.
  A parser therefore needs the local DTD. `dblp_select.py` loads it through expat's
  external-entity hook and fails on any undeclared entity. It never fetches anything over the network.
- Records are often concatenated on one line (`</incollection><incollection ...>`), so the
  scripts split records by expat byte offsets, not by lines.
- Decompressed size: 5,408,968,366 bytes.

## Top-level record types (whole dump, 13,115,316 records)

| Element | Count | ScholarChain use |
|---|---:|---|
| `article` | 4,521,786 | Publication (eligible type) |
| `inproceedings` | 4,001,570 | Publication (eligible type) |
| `www` | 4,243,328 | not used (person/homepage records) |
| `phdthesis` | 158,395 | not used |
| `incollection` | 71,264 | not used |
| `proceedings` | 65,862 | not used |
| `data` | 31,430 | not used |
| `book` | 21,654 | not used |
| `mastersthesis` | 27 | not used |

No top-level `person` element was observed, although the DTD declares one.

## Record attributes (50K selection)

| Attribute | Seen on | Notes |
|---|---|---|
| `key` | all 50,000 | Unique in the whole dump: no duplicate keys among 13,115,316 records |
| `mdate` | all 50,000 | `YYYY-MM-DD` last-modified date |
| `publtype` | 6,738 articles | `informal` 6,696 (e.g. CoRR preprints), `software` 35, `data` 6, `edited` 1 |

## Child elements of selected records (records containing / total occurrences)

| Field | article (27,554) | inproceedings (22,446) | Notes |
|---|---|---|---|
| `author` | 27,554 / 110,891 | 22,446 / 85,246 | Ordered. Carries an `orcid` attribute in 58,366 of 196,137 author occurrences. Names may end in a DBLP homonym number (e.g. `Jiangtao Li 0001`) |
| `title` | 27,554 / 27,554 | 22,446 / 22,446 | Mixed content. Inline `<i>` 215, `<sub>` 138, `<sup>` 121 in the 50K selection. 38 selected titles contain `$` (TeX) |
| `year` | 27,554 | 22,446 | All digits in every eligible record |
| `journal` | 27,554 | -- | Venue for articles |
| `booktitle` | -- | 22,445 | Venue for inproceedings (one selected record has none: `conf/bpm/GrigoreTPJ25`) |
| `ee` | 27,554 / 32,590 | 22,446 / 27,431 | Electronic edition URLs. Optional `type` attribute: `oa` 14,326, `archive` 99 |
| `url` | 27,554 | 22,446 | DBLP-relative table-of-contents URL |
| `pages` | 20,080 | 22,120 | Free text |
| `volume` | 27,553 | -- | |
| `number` | 15,788 | 2 | |
| `crossref` | 22 | 22,431 | Key of the parent `proceedings` record |
| `month` | 5,091 | 3 | |
| `stream` | 6,186 | 1,177 | DBLP stream key |
| `cite` | 8 / 256 | 24 / 433 | Optional `label` attribute (125) |
| `cdrom`, `note`, `publnr` | rare | rare | |

Inside the 50K extract, the hosts of `<ee>` values were `https://doi.org` (50,186),
`https://www.wikidata.org` (5,825), `https://doi.ieeecomputersociety.org` (2,656 https + 346 http),
`https://aclanthology.org` (361), and others. Only `doi.org` / `dx.doi.org` (optionally `www.`) URLs
are treated as DOIs.

## DOI canonicalisation (as implemented)

Strip `http(s)://[www.][dx.]doi.org/`, percent-decode, trim, lowercase. The value must start with
`10.` and contain no whitespace. The first such `<ee>` in document order is the record's DOI.
DOI prefixes in the 50K selection: `10.1109` 16,907; `10.1007` 7,608; `10.48550` (arXiv, a
DataCite DOI) 6,637; `10.1016` 5,302; `10.1145` 3,853; `10.3390` 1,179; others.

## Mapping to ScholarChain (unchanged intent; domain types are owned by `domain-engineer`)

```
article / inproceedings -> Publication
  source_id : "dblp:" + @key
  title     : flattened <title> text, entities resolved
  authors   : ordered <author> text (+ @orcid when present)
  year      : <year>
  venue     : <journal> (article) | <booktitle> (inproceedings)
  doi       : canonical DOI from the first doi.org <ee>
```

## Known quirks observed

1. 21 canonical DOIs are shared by 44 eligible records, often `...a` key variants of the same
   paper (e.g. `journals/ijcv/PanPYHG26` / `journals/ijcv/PanPYHG26a`). The selector keeps one per
   DOI.
2. Years run from 1938 to 2027 in the eligible pool. 2027 holds forthcoming-issue records, and 2026
   is incomplete at this snapshot.
3. CoRR (arXiv) is the largest venue in both selections (6,633 of 50K; 1,306 of 10K). These records
   have `publtype="informal"` and `10.48550/arxiv.*` DOIs.
4. `www` records (4.2M) are person/homepage entries and are not publications.
