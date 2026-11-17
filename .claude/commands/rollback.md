# Rollback

Route rollback to `deploy-engineer` with limited autonomous actions.

## Responsible agent

`deploy-engineer`

## Prerequisites (gates)

1. A deployment is active and needs rollback.
2. Human architect has approved the rollback (or smoke-test failure triggered
   automatic rollback per deployment policy).
3. The rollback script (`scripts/rollback.sh`) exists and was reviewed by
   `infra-engineer`.

## Autonomous actions allowed

- Execute `scripts/rollback.sh` with the previous known-good commit/version.
- Run `scripts/smoke-test.sh` after rollback.

## Actions NOT allowed without human approval

- Any data migration or repair.
- Any infrastructure destruction.
- Rollback across major version boundaries.
- Any action outside the approved rollback script.

## Procedure

1. Orchestrator invokes `deploy-engineer` with rollback parameters.
2. `deploy-engineer` executes `scripts/rollback.sh`.
3. `deploy-engineer` runs `scripts/smoke-test.sh` and reports results.
4. If rollback fails, escalate to human architect immediately.

## Escalation

- Rollback failure: human architect.
- Data integrity concerns: `incident-responder` and human architect.
