# Experiment

Route controlled experiments to `experiment-engineer`.

## Responsible agent

`experiment-engineer`

## Prerequisites (gates)

1. An experiment manifest has been committed under `experiments/`.
2. The experiment design has been reviewed.
3. Production algorithms must not be altered to improve reported results.
4. Raw result immutability is guaranteed.

## Procedure

1. Orchestrator invokes `experiment-engineer` with the experiment specification.
2. `experiment-engineer` verifies the committed manifest.
3. `experiment-engineer` executes the experiment through approved scripts.
4. `experiment-engineer` collects raw results (immutable).
5. `experiment-engineer` reports results.

## Escalation

- Results suggest production algorithm changes: route to the owning module
  engineer through the orchestrator.
- Cost bounds exceeded: halt and report to human.
- Unexpected infrastructure behavior: escalate to `infra-engineer` and human.
