# Phase Evidence Format

This directory contains phase evidence for ScholarChain agent runs. Each phase
gets a subdirectory `phase-NN/`.

## Required files per phase

```text
docs/agent-runs/phase-NN/
  prompt.md              -- the work order given to the agent
  agent-report.md        -- the agent's final structured report
  review-report.md       -- code-reviewer findings (when applicable)
  test-report.md         -- test-engineer results (when applicable)
  human-gate.md          -- human ratification decision
  repair-01.md           -- repair records (when applicable, incrementing)
  commit.txt             -- the accepted commit hash
```

## Human gate file format

The `human-gate.md` file must state:

- What was reviewed.
- What was accepted or rejected.
- Any manual changes made.
- Any remaining risk.
- The accepted commit hash.

## What is stored

- Work orders (prompts).
- Agent final reports with structured fields.
- Deterministic command output.
- Review findings.
- Human decisions and ratification statements.

## What is NOT stored

- Model hidden reasoning or chain-of-thought traces.
- Intermediate drafts or scratchpad content.
- Secret values or credentials.

## Ownership

Files under `docs/agent-runs/` are **human-owned evidence**. The orchestrator
assembles agent reports here. No implementation agent may rewrite a human
decision. Only `docs/agent-runs/README.md` is owned by `factory-engineer`.
