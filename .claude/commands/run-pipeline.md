# Run Pipeline

Run a bounded ScholarChain data pipeline through approved scripts.

## Responsible agent

`deploy-engineer` (execution) with scripts authored by `infra-engineer`.

## Prerequisites (gates)

1. The pipeline script (`scripts/run-bounded-pipeline.sh`) has been reviewed
   and approved by `infra-engineer` and human architect.
2. Input data bounds have been specified (max records, max cost).
3. The working tree is clean.
4. `sbt check` has passed.

## Procedure

1. Orchestrator invokes `deploy-engineer` with pipeline parameters.
2. `deploy-engineer` verifies the clean tree and approved script.
3. `deploy-engineer` executes `scripts/run-bounded-pipeline.sh` with the
   specified bounds.
4. `deploy-engineer` monitors for cost or time limit breaches.
5. `deploy-engineer` reports pipeline results.

## Escalation

- Cost or time limit exceeded: halt and report to human.
- Data quality issues: escalate to `dataset-engineer` and human.
- Infrastructure failures: escalate to `infra-engineer` and human.
