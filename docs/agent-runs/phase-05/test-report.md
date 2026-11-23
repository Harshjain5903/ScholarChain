# Phase 05 – Test Report

RECONSTRUCTED 2026-11-23. No `test-report.md` existed for this phase. This file was assembled by the orchestrator from
(a) test results stated in the original subagent reports (quoted in `agent-report.md` and the repair records), and
(b) a retroactive re-run of `sbt check` on the committed revisions, observed by the orchestrator on 2026-11-23.

## A. Results stated at the time (agent-reported, not independently observed)

From the original reports: after implementation, 317 tests passed but the agent's own `sbt check` **FAILED** on 34 unformatted hadoop files; after the fix round, 122 tests and `sbt check` reported passing; after the formatting fix, 104 entity-resolution tests passed.

## B. Retroactive verification of the committed revisions (observed 2026-11-23)

Method: `git archive <sha>` was extracted into an empty directory under the orchestrator's session scratchpad (no `.git`, so no untracked or uncommitted files), then `sbt -batch check` ran there on 2026-11-23 with OpenJDK 17.0.21 and the sbt launcher from Homebrew. This re-observes the committed tree; it is not a record of what was run at the time. `.scalafmt.conf` sets `project.git = true`, so in a normal working tree the format check skips files Git does not track yet; a fresh checkout tracks every file, which is what this run models.

| Revision | What it is | On GitHub `main` | `sbt -batch check` exit | Test suites (passed/failed per module run) | First error |
|---|---|---|---|---|---|
| `cdf0e5d8e40c86a4990b67722bdbc469fb065cd1` | Phase 05 implementation | yes | 1 | no tests ran | scalafmtCheck failed: entityResolution Compile: 9 files, entityResolution Test: 7 files |
| `1b36f31f51d56ade95eb6af8f050fd045d897064` | Phase 05 formatting fix | yes | 0 | 104/0, 97/0, 122/0 | – |

## C. Checks NOT RUN

- Any AWS or EMR execution. Checked 2026-11-23: the Terraform backend was never initialized in this checkout, no plan file exists, and `aws sts get-caller-identity --profile scholarchain` (2026-11-23T16:57:05Z) returned an expired-session error; no transcript shows an AWS run.
- Gold-set evaluation on human-labelled pairs (the gold set is synthetic).
- Threshold sensitivity and error analysis on real data.

