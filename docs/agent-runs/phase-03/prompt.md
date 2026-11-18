# Phase 3 Work Orders

## Part 1: Dataset-Engineer
Create datasets/dataset-manifest.yaml for DBLP, OpenAlex, ORCID, and Crossref with all 20 required fields. Create bounded download scripts, schema docs, and sample JSON files.

## Part 2: Domain-Engineer
Create immutable Scala domain records (27 types), 6 enums, stable ID functions, config loader with HOCON, uPickle serialization, and contract tests with golden files.

## Fix Round
Fixed 4 high, 6 medium, 4 low code-review findings: added provenance to ResearcherMetrics, require guards on case class bodies for validation, config error tests, python3 -I flags, cross-dataset DOI link, Crossref license fix.
