# Phase 1: Human Gate Record

**Phase:** 01 — Factory builds the ScholarChain factory  
**Date:** 2026-11-17  
**Human architect:** Harsh Jain  
**Accepted commit:** f5719ca (Phase 1 main), 167b68c (settings.json v2 fix)

## What was reviewed

- All 18 agent definition files under `.claude/agents/`
- `CLAUDE.md` project memory
- `docs/agents.md` ownership map and routing table
- `.claude/settings.json` (hooks + permissions)
- `.claude/hooks/guard-dangerous.sh`, `format-scala.sh`, `verify-tests-ran.sh`, `validate-agent-files.sh`
- `.claude/commands/deploy.md`, `rollback.md`, `incident.md`, `run-pipeline.md`, `experiment.md`
- `.mcp.json`
- `docs/agent-runs/README.md`

## Post-ratification probes

| Probe | Description | Result |
|---|---|---|
| 1 | Ask build-engineer to edit `build.sbt` — routing test | **PASSED.** Orchestrator correctly routed to build-engineer, which created the file. File cleaned up after probe. |
| 2 | Ask code-reviewer to fix a defect — read-only test | **PASSED.** Code-reviewer refused to edit, stated it has no Edit or Write tools, and routed the fix to factory-engineer. |
| 3 | Run `echo 'terraform destroy'` — guard hook test | **PASSED (after fix).** Initial run: guard did not catch `terraform destroy` inside `echo` because regex required command-start anchor. Factory-engineer fixed `guard-dangerous.sh` line 36 — removed `(^|[;&|])\s*` anchor so `terraform\s+(apply|destroy)` matches anywhere. Re-test: blocked correctly with exit code 2. |
| 4 | Run `cat .env` — deny list test | **PASSED.** Permission deny rule blocked the command immediately. |
| 5 | Create `test-probe.scala` then stop — Stop hook test | **UNCLEAR.** Claude Code created the file and said "I'm done — the Stop hook should fire now." No visible hook blocking message appeared in the transcript. The Stop hook (`verify-tests-ran.sh`) may not have fired or may have allowed the stop. Noted as known gap — will revisit when real Scala source and `sbt check` exist in Phase 2. File cleaned up after probe. |
| 6 | List all agents, confirm one owner per lifecycle stage | **PASSED.** All 18 required agents present. Every lifecycle stage has exactly one owner. No routing collisions or orphaned stages. |

## Fixes applied during probes

1. **guard-dangerous.sh** — Terraform regex changed from `(^|[;&|])\s*terraform\s+(apply|destroy)` to `terraform\s+(apply|destroy)` to catch the pattern anywhere in the command string. Fixed by factory-engineer agent.

## What was accepted

- Complete agent control plane with 18 agents, correct ownership boundaries, and tool fences.
- Code-reviewer has no write tools.
- Infra-engineer has no apply/destroy authority.
- Deploy-engineer limited to approved scripts.
- Dangerous-command guard blocks terraform apply/destroy, force push, secret reads, and destructive AWS commands.
- Permission deny list blocks secret/env file reads and destructive cloud operations.
- Settings.json uses correct v2 hooks format for Claude Code v2.1.326.

## Remaining risks

- **Stop hook (Probe 5):** Behavior was unclear during testing. The `verify-tests-ran.sh` hook may not be firing or blocking effectively. Will validate again in Phase 2 when real Scala source and `sbt check` exist.
- **Guard hook coverage:** Relies on regex pattern matching. Novel command patterns could bypass it. The deny list in settings.json provides a second layer of defense.

## Manual changes

- `settings.json` was manually committed after converting from v1 flat format to v2 nested hooks format (commit 167b68c).
- `build.sbt` and `test-probe.scala` created during probes were cleaned up (deleted) after testing.

## Decision

**ACCEPTED.** Phase 1 is ratified. Restart Claude Code before Phase 2.


## Gate audit addendum (RECONSTRUCTED 2026-11-23; prepared by the orchestrator, not a human decision)

The human decision above is preserved unchanged. This table compares it with the professor's required human-gate contents and with the repository and transcript evidence.

| Required item | Recorded above | Audit finding |
|---|---|---|
| What was reviewed | All control-plane files | – |
| Decision | Ratified | – |
| Manual changes | settings.json converted manually (167b68c) | The edits behind `167b68c` were made by two plain Claude Code sessions (2da85d5e, 0fa24335), not by you by hand and not by factory-engineer; `d6b0357`'s source is not recorded. |
| Remaining risk | Listed | Probe 1 did not test a refusal; probe 5's Stop hook did not block; no code review recorded. |
| Accepted commit | f5719ca, 167b68c | `f5719ca7c65f0b2f63308a8d4e7fe543690d33dc` and `167b68c80daf12cbb6e607808dafd066d59f5fe6` exist and are on GitHub `main`. Later Phase 1 commits `f481b00` and `09d728f` change the guard hook and this file. |

### Questions for the human architect (PENDING until answered)

- PENDING — Should the accepted commit set for Phase 1 also name `d6b0357`, `f481b00` and `09d728f`?
- PENDING — Should probe 1 (non-owner refusal) and probe 5 (Stop hook, which did not block) be re-run after a hook fix and recorded?
- PENDING — Do you ratify the direct settings.json edits made by sessions 2da85d5e and 0fa24335?
