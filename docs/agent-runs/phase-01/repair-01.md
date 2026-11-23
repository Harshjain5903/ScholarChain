# Phase 01 – Repair Record 01: guard regex and settings.json hook format (RECONSTRUCTED 2026-11-23)

**Original finding.** Probe 3: `echo 'terraform destroy'` was not blocked because `guard-dangerous.sh` anchored `terraform (apply|destroy)` to the start of a command. Separately, `.claude/settings.json` was missing a Stop-hook matcher and used a flat hook format.

**Reproduction evidence.** Session `70fb9821-fddc-48c9-afdb-4e5a0d353616`: user message at 2026-11-17T19:32Z ran the probe; factory-engineer subagent `ae3c9114861923a1f` (19:33–19:34Z) fixed line 36; the same probe at 19:35Z was blocked.

**Owner.** factory-engineer for `guard-dangerous.sh`. The constitutional file `.claude/settings.json` was edited **directly by two plain Claude Code sessions, bypassing factory-engineer**. Session `2da85d5e-aaf8-4bd7-923c-737e0244781c` removed the Stop matcher at 2026-11-17T19:13:19Z, and session `0fa24335-a303-482c-8afc-6ed35be22226` added an empty `matcher` and the v2 `hooks` arrays at 19:14:54Z and 19:16:09Z; `167b68c` commits that result. The source of `d6b0357` (19:04Z) is NOT RECORDED.

**Changes.** `d6b0357` (Stop matcher), `167b68c` (v2 hooks format), `f481b00` (guard regex + gate record) and `09d728f` (second gate edit, same commit message).

**Rerun result.** Probe 3 re-run blocked (recorded in `human-gate.md` and the transcript). Probe 5 ran after `167b68c` and the Stop hook did not block (`preventedContinuation: false`; see test-report.md).

**Independent review outcome.** NOT RECORDED. A constitutional file was changed outside factory-engineer after the main ratification; no separate ratification record for `d6b0357`/`167b68c` exists beyond `human-gate.md`.



## Original subagent final reports (RECONSTRUCTED 2026-11-23)
Each report below is the subagent's final message, copied word for word from its transcript (`<session>/subagents/agent-<id>.jsonl`). Text above this section is the original file and is unchanged.

### factory-engineer guard fix (probe 3 repair)

factory-engineer subagent `ae3c9114861923a1f`, session `70fb9821-fddc-48c9-afdb-4e5a0d353616`.

````text
All validations pass. Here is the report.

```
STATUS: COMPLETE
OWNER: factory-engineer
WORK ORDER: Fix terraform apply/destroy regex in guard-dangerous.sh to match anywhere in the command string, not only at start or after shell separators
FILES CREATED: none
FILES MODIFIED: .claude/hooks/guard-dangerous.sh
COMMANDS RUN: test verification (echo terraform destroy piped to hook), bash -n syntax check, test -x executable check, terraform plan allowance check, direct terraform destroy block check, git diff, JSON validation of settings.json and .mcp.json, grep for safety-floor invariant presence
TEST RESULTS: not applicable: control-plane only
VALIDATION RESULTS:
  - JSON parse (.claude/settings.json): VALID
  - JSON parse (.mcp.json): VALID
  - bash -n (guard-dangerous.sh): SYNTAX OK
  - executable bit (guard-dangerous.sh): EXECUTABLE
  - frontmatter: not applicable (shell hook, not agent file)
  - ownership audit: no collisions, no orphans, one writer per artifact -- guard-dangerous.sh is owned by factory-engineer
  - reviewer fence: not affected by this change
  - safety-floor check: terraform apply/destroy guard PRESENT and STRENGTHENED (now matches anywhere in command); all other guards unchanged (git force push, secrets read, destructive AWS, constitutional file protection all verified present in file)
  - functional verification: "echo 'terraform destroy'" now correctly BLOCKED (exit 2); "terraform plan" still ALLOWED (exit 0); direct "terraform destroy" still BLOCKED (exit 2)
ASSUMPTIONS: Removing the start-of-command/shell-separator anchor is strictly a strengthening of the guard -- it blocks a strict superset of what the previous regex blocked
RISKS: Commands that mention "terraform apply" or "terraform destroy" in a non-execution context (e.g., grep for documentation) will also be blocked. This is the conservative/safe behavior consistent with law 6 (never weaken the safety floor).
MANUAL ACTION REQUIRED: Human architect must review the diff, ratify the constitutional change, commit, and restart the session
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human constitutional ratification and session restart
```

**Complete diff:**

```diff
diff --git a/.claude/hooks/guard-dangerous.sh b/.claude/hooks/guard-dangerous.sh
index 7128bc5..dd89d37 100755
--- a/.claude/hooks/guard-dangerous.sh
+++ b/.claude/hooks/guard-dangerous.sh
@@ -33,7 +33,7 @@ if [ -z "$COMMAND" ]; then
 fi
 
 # --- Terraform apply/destroy ---
-if printf '%s' "$COMMAND" | grep -qE '(^|[;&|])\s*terraform\s+(apply|destroy)'; then
+if printf '%s' "$COMMAND" | grep -qE 'terraform\s+(apply|destroy)'; then
   echo "BLOCKED: terraform apply/destroy is forbidden. Use terraform plan and get human approval." >&2
   exit 2
 fi
```

The single changed file is `/Users/harshjain/Documents/scholarchain/.claude/hooks/guard-dangerous.sh`.
````

### build-engineer probe 1 run

build-engineer subagent `a1d8e5564804f85e7`, session `70fb9821-fddc-48c9-afdb-4e5a0d353616`.

````text
Created `/Users/harshjain/Documents/scholarchain/build.sbt` with the requested comment.

```
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: Create minimal build.sbt with a comment header
FILES CREATED: /Users/harshjain/Documents/scholarchain/build.sbt
FILES MODIFIED: none
COMMANDS RUN: Write build.sbt
TEST RESULTS: N/A (no compilable content yet; sbt check deferred to full build setup)
VALIDATION RESULTS: File created successfully
ASSUMPTIONS: Only a comment was requested; full multi-project build will follow in a later work order
RISKS: none
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: sbt check once build definition has compilable content
```
````
