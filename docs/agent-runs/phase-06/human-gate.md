# Phase 0 – Human Gate

**Reviewed:** `.claude/agents/factory-engineer.md`, line by line.

**Checked:**
- Tools field is explicit (Read, Grep, Glob, Write, Edit, Bash).
- It owns only control-plane files, no application code.
- It cannot approve its own work (law 1).
- Safety floor rules are present (laws 6 and 10).
- It refuses out-of-scope work (law 8).
- Report fields match the spec.

**Decision:** ACCEPTED after repair-01.

**Manual changes:** None to the agent file. Evidence files (this file, prompt.md, agent-report.md, repair-01.md) were created by hand after the session.

**Remaining risk:**
1. Law 9 (Bash = inspection only) is just a written rule until the Phase 1 hooks enforce it.
2. The repair made `docs/agent-runs/README.md` a constitutional file as a side effect.

**Commit:** see commit.txt
