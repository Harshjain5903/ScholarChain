# ScholarChain

ScholarChain is an academic-knowledge-graph platform built with **Scala 3**,
**Hadoop MapReduce**, **Apache Spark**, **AWS managed services** (S3, Neptune,
DynamoDB, S3 Vectors, EMR, ECS Fargate, Step Functions, SQS, Bedrock, Secrets
Manager, KMS, CloudWatch), and a **Graph-RAG** query layer. The build tool is
**sbt** with a multi-project layout. All infrastructure is defined in
**Terraform**.

## Module dependency boundaries

```
core                  -- shared domain records, stable IDs, serialization, config
hadoop                -- MapReduce jobs; depends on core
entity-resolution     -- blocking, matching, calibration; depends on core
spark                 -- graph construction and analytics; depends on core
storage               -- S3, Neptune, DynamoDB, S3 Vectors adapters; depends on core
rag                   -- query plans, retrieval, evidence, prompts; depends on core, storage
services              -- REST routes, health, runtime composition; depends on core, rag, storage
```

No module may depend on a module above it in this list (for example `core` must
never import from `hadoop`). Cross-module integration is verified by contract
tests.

## Definition of done

A change is complete when **`sbt check`** passes. The `check` alias runs format
verification, compilation, all tests (unit, contract, integration where
applicable), and updates the test-run marker at `.claude/.last-test-run`.

No agent may report implementation work as COMPLETE unless it observed `sbt
check` succeed in the current session.

## One owner per artifact

Every artifact class in this repository has exactly one creating and owning
agent. The authoritative ownership map is in `docs/agents.md`. An agent asked
to edit another agent's artifact must decline and name the correct owner.

## Feature tests required

Every behavior change must be accompanied by feature tests that exercise the new
or altered behavior. Test-only changes do not require additional tests.

## Provenance required

Every data transformation and every runtime claim must carry provenance metadata
that traces back to the source dataset, the transformation step, and the agent
or component that produced it.

## Secrets management

Secrets are retrieved only through AWS Secrets Manager or approved local
placeholder files listed in `.gitignore`. No secret value may appear in source,
configuration committed to Git, agent files, or logs. `.env` files must not be
read by any agent or hook.

## Infrastructure through Terraform only

All AWS infrastructure is defined in Terraform under `infra/terraform/`.
Direct cloud-console mutation is forbidden as an implementation path. Console
reads for inspection are permitted.

## Constitutional files

The files under `.claude/`, `CLAUDE.md`, `docs/agents.md`, and `.mcp.json` are
constitutional. Changes require human ratification and a session restart before
they take effect.

@docs/agents.md
