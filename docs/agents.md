# ScholarChain Agent Ownership and Routing

This file is the authoritative artifact-ownership map for the repository.
It is a constitutional file owned by `factory-engineer`.

## Artifact ownership table

| Agent | Creates and owns | Must not do |
|---|---|---|
| `factory-engineer` | `CLAUDE.md`, `docs/agents.md`, `.claude/agents/*`, `.claude/hooks/*`, `.claude/commands/*`, `.claude/settings.json`, `.mcp.json`, `docs/agent-runs/README.md` | Application, data, build, infrastructure, or deployment work |
| `build-engineer` | `build.sbt`, `project/*`, `.scalafmt.conf`, local compose files (`docker-compose*.yml`), packaging config, `.gitignore` | Application source, Terraform, deployment |
| `domain-engineer` | `modules/core/**` -- shared domain records, stable identifiers, serialization contracts, configuration contracts | Dataset retrieval, distributed jobs, storage adapters, service routes, infrastructure |
| `dataset-engineer` | `datasets/**` -- dataset manifest, download/sampling scripts, schema notes, license records | Hadoop analytics, Spark analytics, entity-match policy |
| `hadoop-engineer` | `modules/hadoop/**` -- MapReduce jobs, Hadoop integration tests, counters, rejected-record formats | Spark jobs, storage clients, service APIs |
| `entity-resolution-engineer` | `modules/entity-resolution/**` -- blocking keys, match features, thresholds, calibration, match decision schemas | Graph analytics, API synthesis, infrastructure |
| `spark-engineer` | `modules/spark/**` -- graph construction, graph analytics, evidence-path generation | Hadoop parser ownership, runtime API, Terraform |
| `storage-engineer` | `modules/storage/**` -- loaders/adapters for S3, Neptune, DynamoDB, S3 Vectors, optional OpenSearch | Terraform, answer synthesis, runtime HTTP routes |
| `rag-engineer` | `modules/rag/**`, `prompts/runtime/**`, `prompts/schemas/**` -- query plans, retrieval fusion, evidence bundles, prompts, claim verification | HTTP transport, cloud deployment, dataset acquisition |
| `service-engineer` | `modules/services/**` -- REST models, routes, health checks, runtime composition | Hadoop/Spark algorithms, Terraform, agent constitution |
| `test-engineer` | `tests/**` -- adversarial and cross-module tests | Production implementation; no edits outside test trees |
| `code-reviewer` | No implementation artifacts; review reports only | Any write or edit (read-only agent) |
| `infra-engineer` | `infra/terraform/**`, `scripts/*.sh` -- Terraform modules/environments, deployment/smoke-test/reproduction/cleanup scripts, optional GitHub workflows (`.github/**`) | `terraform apply`, live deployment, application source |
| `deploy-engineer` | Deployment execution reports and release records | Authoring Terraform or deployment scripts |
| `experiment-engineer` | `experiments/**`, `load-tests/**` -- experiment manifests, raw result collectors | Altering production algorithms to improve reported results |
| `documentation-engineer` | `README.md`, `docs/**` (except `docs/agents.md`, `docs/agent-runs/**`, `docs/incidents/**`), `reports/**` -- final report, diagrams, demo script | Inventing results, changing implementation, editing constitutional files |
| `incident-responder` | `docs/incidents/**` -- diagnosis reports, approved rollback and bounded recovery actions | Schema changes, destructive data repair, infrastructure authorship |
| `dependency-updater` | Version values in the approved dependency ledger (`project/Dependencies.scala` or equivalent) and dependency update reports | Build structure, application features, major upgrades without approval |

### Special ownership notes

- **`docs/agent-runs/**`** (except `docs/agent-runs/README.md`): Human-owned
  phase evidence. The orchestrator assembles agent reports there. No
  implementation agent may rewrite a human decision.
- **`docs/agent-runs/README.md`**: Owned by `factory-engineer`.
- **`.claude/.last-test-run`**: Runtime marker written by sbt `markTestRun`
  task (owned by `build-engineer` as part of the build definition). This is an
  explicit exception to `factory-engineer` ownership of `.claude/**`. The
  marker file is generated build output, not a constitutional file.
- **`prompts/**`**: Owned by `rag-engineer` (runtime prompts and schemas).
- **`reports/**`**: Owned by `documentation-engineer` (final report and
  deliverables).

## Lifecycle-to-agent routing table

| Lifecycle stage | Responsible agent |
|---|---|
| Agent system, hooks, permissions, MCP config | `factory-engineer` |
| Build definition, sbt config, formatting config | `build-engineer` |
| Domain records, stable IDs, config contracts | `domain-engineer` |
| Dataset acquisition, manifest, sampling | `dataset-engineer` |
| Hadoop MapReduce jobs | `hadoop-engineer` |
| Entity matching, blocking, calibration | `entity-resolution-engineer` |
| Spark graph construction and analytics | `spark-engineer` |
| Storage adapters and loaders | `storage-engineer` |
| RAG query plans, retrieval, evidence, prompts | `rag-engineer` |
| REST services, health checks, composition | `service-engineer` |
| Adversarial and cross-module tests | `test-engineer` |
| Code review (read-only) | `code-reviewer` |
| Terraform, infrastructure scripts | `infra-engineer` |
| Deployment execution | `deploy-engineer` |
| Experiments and load tests | `experiment-engineer` |
| Documentation, README, final report | `documentation-engineer` |
| Incident diagnosis and recovery | `incident-responder` |
| Dependency version updates | `dependency-updater` |

## Routing rules

1. Subagents do not invoke one another. All routing passes through the
   orchestrator.
2. An agent asked to work outside its owned artifacts must decline and name the
   correct owner from this table.
3. The orchestrator decomposes work, invokes the correct specialist, reads
   reports, routes blocked work, and presents results to the human architect.
4. The orchestrator must not perform a specialist's work inline.

## Escalation rules

The following actions require escalation to the **human architect**:

| Trigger | Required action |
|---|---|
| Constitutional change (any file under `.claude/`, `CLAUDE.md`, `docs/agents.md`, `.mcp.json`) | Human ratification and session restart |
| Data destruction or irrecoverable mutation | Human approval before execution |
| Security-sensitive change (secrets, IAM, KMS, network) | Human review and approval |
| `terraform apply` or `terraform destroy` | Human reviews plan, cost, account, region; explicit approval |
| Deployment to any environment | Human verifies plan checksum, account, region, cost bounds |
| Expensive scale change (EMR fleet resize, large provisioned capacity) | Human cost review and approval |
| Ambiguous artifact ownership | Human architect resolves and updates this file |
| Incident involving data integrity or security | `incident-responder` escalates to human |
| Major dependency upgrade | Human approval required |
