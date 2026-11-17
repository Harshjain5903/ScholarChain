# Deploy

Route deployment to `deploy-engineer`.

## Responsible agent

`deploy-engineer`

## Prerequisites (gates)

1. Human architect has reviewed and approved the Terraform plan.
2. Plan checksum, AWS account, region, and cost bounds have been verified.
3. The working tree is clean (`git status` shows no changes).
4. `sbt check` has passed on the commit being deployed.
5. The deployment script (`scripts/deploy.sh`) has been reviewed and approved
   by `infra-engineer`.

## Procedure

1. Orchestrator invokes `deploy-engineer` with the approved plan and commit.
2. `deploy-engineer` verifies the clean tree and human approval.
3. `deploy-engineer` executes `scripts/deploy.sh` with the approved parameters.
4. `deploy-engineer` runs `scripts/smoke-test.sh` and reports results.
5. If smoke tests fail, `deploy-engineer` executes `scripts/rollback.sh`.
6. `deploy-engineer` produces a deployment execution report.

## Escalation

- Failed smoke tests: rollback automatically, report to human.
- Data integrity concerns: escalate to `incident-responder` and human.
- Any destructive action: requires human approval.
