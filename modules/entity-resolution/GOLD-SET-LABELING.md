# Entity-resolution gold set: labeling guide

Owner: `entity-resolution-engineer`. Labels are assigned by a **human reviewer only**.
No agent, script or model may fill `human_label`.

## Workflow

1. Generate candidates (selection and split only, no labels):

   ```
   sbt "entityResolution/runMain edu.scholarchain.entityresolution.GoldSetSampler \
     --er-output <runner output dir> --work-records <hadoop export/work_records> \
     --output <path outside modules/>/gold-candidates.csv [--seed 20261124] [--size 170]"
   ```

   This writes the CSV and `<csv>.manifest.json` (seed, input sha256, strata counts).
   The output path must not be inside any `modules/` directory.

2. Label every row: set `human_label` to `MATCH`, `NON_MATCH` or `UNSURE`, and write
   the evidence you used in `notes`.

3. Do **not** add, delete, reorder-by-editing-ids, or change `pair_id`, `split`,
   `stratum`, `hard_negative_stratum`, `matcher_*`, `policy_version` or `sample_seed`.
   The evaluator recomputes the split from (pair_id, stratum, seed) and refuses the file
   if any row was added, removed or re-split. If a row is unusable, label it `UNSURE`
   and explain in `notes`. Sorting rows in a spreadsheet is fine.

4. Evaluate:

   ```
   sbt "entityResolution/runMain edu.scholarchain.entityresolution.GoldSetEvaluation \
     --gold <labeled csv> --output <dir outside modules/>"
   ```

   It refuses fewer than 170 labeled rows, a labeled split other than 102 calibration /
   68 holdout, or a split without a MATCH or a hard negative. `--preliminary` runs anyway
   and stamps every output `PRELIMINARY`.

## What counts as the same person

Label `MATCH` when both mentions refer to **the same real human being**, regardless of
how the name is written:

- Name variants of one person are a MATCH: initials vs. full given name
  ("W. Wang" / "Wei Wang"), diacritics or transliteration ("Müller" / "Mueller"),
  hyphenation or spacing ("Tsong-Yueh" / "Tsong Yueh"), middle names present or absent,
  a name change after marriage when evidence links the two.
- Different people with identical names are a `NON_MATCH`, even when the names, venues
  and topics are identical. Common East Asian, South Asian and Western names
  ("Wei Wang", "Yang Liu", "J. Smith") are frequently shared by many researchers.
- Same research group, advisor/student, or co-authors of each other are **not** evidence
  of identity. Two people who co-author a paper are different people.

## Evidence: what to check, in order

Use evidence that is **independent of the matcher's own decision rule**. The matcher
already uses DBLP homonym suffixes (e.g. "Wei Wang 0001") and ORCIDs from the CSV.
Copying those into the label would leak the matcher's rule into the gold set and make
the `exact_id` and `dblp_person_key` paths look perfect by construction.

Therefore:

- **Do not** label from the suffix digits in `raw_name_a/b` or from `orcid_a/b`
  equality alone.
- **Do** open the primary sources and judge from the person's actual record:

1. **The papers themselves** (DBLP record -> DOI / publisher page / PDF). Compare
   affiliations, e-mail addresses, and author footnotes on both papers. Same
   institution at overlapping times plus overlapping topic is strong evidence;
   different institutions in the same year is strong evidence against.
2. **DBLP person pages** (`https://dblp.org/pid/...`, reachable by clicking the author
   name on either paper's DBLP record). Use them for the *content* they expose: the
   affiliation note, linked homepage, ORCID and the publication list. Check whether
   both papers appear on one page with a consistent career trajectory. Be aware that
   DBLP person pages are themselves an algorithmic-plus-curated disambiguation and can
   be wrong; disambiguated pages often show a "not to be confused with" note. Record
   which page you used in `notes`.
3. **ORCID records** (`https://orcid.org/<id>`). Treat ORCID as evidence only through
   the record's content: employment history and the works list. A works list
   containing both papers is strong evidence for MATCH. ID equality in the CSV without
   looking at the record is not acceptable evidence.
4. **Personal or institutional homepages, Google Scholar profiles** listing both
   papers.
5. **Co-author continuity** (`coauthors_a/b`): shared co-authors across years
   supports MATCH but is not conclusive on its own; no shared co-authors is weak
   evidence against.

Write the decisive evidence in `notes`, e.g.
`both on dblp pid/12/3456; affiliation Tsinghua on both PDFs` or
`paper A: ETH Zurich 2021; paper B: Univ. of Sydney 2021, different ORCID records`.

## When to use UNSURE

Use `UNSURE` when, after reasonable checking (about five minutes per pair):

- the papers are inaccessible and no profile lists both papers;
- evidence conflicts (e.g. same DBLP page but different affiliations with no
  plausible move between them);
- the only available evidence is the name itself plus venue/topic similarity;
- the only available evidence is the DBLP suffix or ORCID ID in the CSV.

`UNSURE` rows count as labeled for the 170-row and 102/68 gates but are **excluded** from
precision, recall and F1; the evaluator reports their count per split. Prefer `UNSURE`
over a guess: a wrong gold label damages calibration more than a missing one. If more
than about 15% of a split is `UNSURE`, tell the orchestrator; the sample may need to be
re-drawn with a new seed on a larger input.

## Strata (selection only, never labels)

- `stratum` = `match_path|score_band|hard_negative_stratum`.
- `hard_negative_stratum` is derived from name structure only:
  `dblp_suffix_differs`, `dblp_suffix_one_sided`, `same_full_name`,
  `compatible_given_name`, `incompatible_given_name`. Every pair already shares surname
  and first initial (the blocking key). A `NON_MATCH` in any stratum except
  `incompatible_given_name` counts as a hard negative.
- Strata tell you why a pair was selected. They are not hints about the answer.

## Labeling with the HTML tool

`modules/entity-resolution/tools/gold-label.html` is a single self-contained page
(no network requests, no CDN, no frameworks) that replaces editing the CSV in a
spreadsheet.

1. Double-click `gold-label.html` to open it in a browser (it works from `file://`).
   Append `?selftest=1` to the URL to run the built-in CSV parser self-test.
2. Choose `gold-candidates-10k.csv` with the file picker. A previously downloaded,
   partially labeled `gold-candidates-10k.labeled.csv` is also accepted, which is how
   you resume across sessions.
3. Label each pair with the buttons or keys `1` MATCH, `2` NON_MATCH, `3` UNSURE;
   arrow keys move between rows. Put decisive evidence in the notes box. The matcher's
   score and decision are shown only as "matcher's guess, not ground truth"; no label
   is prefilled.
4. Progress is autosaved to the browser's `localStorage` (keyed by file name and row
   count). On reload, pick the same file and use "Restore unsaved work". "Clear saved
   progress" removes it. Autosave can be blocked or wiped by the browser, so download
   regularly.
5. Click "Download labeled CSV". The file `gold-candidates-10k.labeled.csv` has the same
   columns, row order and quoting as the input; only `human_label` and `notes` change.
   **This downloaded CSV is the evaluator's input.** Do not add, remove or re-split rows.
