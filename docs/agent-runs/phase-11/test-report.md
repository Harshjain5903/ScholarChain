# Phase 11 – Test Report

RECONSTRUCTED 2026-11-23. No `test-report.md` existed for this phase. This file was assembled by the orchestrator from
(a) test results stated in the original subagent reports (quoted in `agent-report.md` and the repair records), and
(b) a retroactive re-run of `sbt check` on the committed revisions, observed by the orchestrator on 2026-11-23.

## A. Results stated at the time (agent-reported, not independently observed)

From the test-engineer report: `sbt check` passed in the working tree (the subagent's last run was at 2026-11-22T13:32:04Z; the 13:33Z runs were the orchestrator's). The gate records 511 tests.

## B. Retroactive verification of the committed revisions (observed 2026-11-23)

Method: `git archive <sha>` was extracted into an empty directory under the orchestrator's session scratchpad (no `.git`, so no untracked or uncommitted files), then `sbt -batch check` ran there on 2026-11-23 with OpenJDK 17.0.21 and the sbt launcher from Homebrew. This re-observes the committed tree; it is not a record of what was run at the time. `.scalafmt.conf` sets `project.git = true`, so in a normal working tree the format check skips files Git does not track yet; a fresh checkout tracks every file, which is what this run models.

| Revision | What it is | On GitHub `main` | `sbt -batch check` exit | Test suites (passed/failed per module run) | First error |
|---|---|---|---|---|---|
| `143711276084eed5967b3b72efdb6fa51912a841` | Phase 11 implementation | yes | 1 | no tests ran | scalafmtCheck failed: entityResolution Test: 1 files, hadoop Test: 1 files, rag Compile: 11 files, services Compile: 7 files, services Test: 1 files, storage Compile: 2 files |
| `7638fde2d65f9beb209f16de5e63472dd561f2fd` | Phase 11 evidence (GitHub `main` head) | yes | 1 | no tests ran | scalafmtCheck failed: entityResolution Test: 1 files, hadoop Test: 1 files, rag Compile: 11 files, services Compile: 7 files, services Test: 1 files, storage Compile: 2 files |

The working-tree pass and the failing committed tree differ: the orchestrator's inline scalafmt runs on `storage`
(2026-11-22T03:31Z, Phase 9) and on `rag` and `services` (04:01Z, Phase 10) were never committed, and the three
adversarial specs were committed unformatted. (Phase 7's inline storage formatting *was* committed as `bb4b183`.)

Additional observation (2026-11-23): in the same `7638fde` export, `sbt -batch "Test/compile" "test"` (format check skipped)
exited 0. Per-module results: 97, 52, 116, 24, 40, 49 and 133 tests passed, 0 failed (511 in total, matching the gate's
count). So on GitHub `main` only the format gate fails.

## C. Checks NOT RUN

- Any AWS or EMR execution. Checked 2026-11-23: the Terraform backend was never initialized in this checkout, no plan file exists, and `aws sts get-caller-identity --profile scholarchain` (2026-11-23T16:57:05Z) returned an expired-session error; no transcript shows an AWS run.
- Bounded AWS integration tests under an approved profile.
- A full read-only repository review.

