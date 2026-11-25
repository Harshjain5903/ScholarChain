# ScholarChain Architecture

Owner: `documentation-engineer`. Every element below was read from the repository: [build.sbt](../build.sbt), `modules/*/src/main`, [infra/terraform/](../infra/terraform/main.tf), [.claude/](../.claude/settings.json) and [docs/agents.md](agents.md). Nothing has been deployed. The cloud diagram shows what Terraform *defines*, not what is running.

## 1. Build-time agent system

```text
                         +----------------------------------+
                         |   Human architect (orchestrator) |
                         |  ratifies constitution, approves |
                         |  plans/deploys/cost/destruction  |
                         +----------------+-----------------+
                                          | work orders (docs/agent-runs/phase-NN/prompt.md)
                                          v
   Claude Code session at repo root  -- loads CLAUDE.md (+ @docs/agents.md),
                                        .claude/settings.json, .mcp.json
                                          |
     +------------------------------------+-------------------------------------+
     | hooks (.claude/settings.json)                                            |
     |   PreToolUse  Bash        -> .claude/hooks/guard-dangerous.sh  (exit 2)  |
     |   PostToolUse Write|Edit  -> .claude/hooks/format-scala.sh               |
     |   Stop                    -> .claude/hooks/verify-tests-ran.sh           |
     | permissions: allow read-only git/aws/terraform fmt|validate|plan, sbt;   |
     |              deny terraform apply|destroy, .env reads, destructive AWS   |
     +------------------------------------+-------------------------------------+
                                          | orchestrator invokes ONE specialist
                                          | (subagents never call each other)
   +-----------------+-----------------+--+--------------+-------------------+
   v                 v                 v                 v                   v
 constitution      build/data        implementation    gates             operations
 factory-engineer  build-engineer    domain-engineer   test-engineer     infra-engineer
                   dataset-engineer  hadoop-engineer   code-reviewer     deploy-engineer
                   dependency-       entity-resolution-  (read-only)     incident-responder
                     updater           engineer                          experiment-engineer
                                     spark-engineer                      documentation-engineer
                                     storage-engineer
                                     rag-engineer
                                     service-engineer
   |                 |                 |                 |                   |
   +-----------------+--------+--------+-----------------+-------------------+
                              | 14-field report (STATUS ... NEXT GATE)
                              v
      orchestrator routes BLOCKED-ON items to the owner named in docs/agents.md
                              |
                              v
      docs/agent-runs/phase-NN/{agent-report,test-report,review-report,
                                 repair-NN,human-gate}.md, commit.txt
                              |
                              v
      definition of done: `sbt check` = scalafmtCheckAll; Test/compile; test;
                          markTestRun -> .claude/.last-test-run
```

The 18 agents, their owned paths and their tool fences are listed in the [README agent catalog](../README.md#agent-catalog) and in [docs/agents.md](agents.md).

## 2. Module dependency graph (sbt)

```text
                 core  (domain records, IdGen, Provenance, config contracts)
        +--------+--------+-----------------+------------------+
        v        v        v                 v                  |
     hadoop  entity-   spark            storage                |
             resolution (sbt id:         (S3, Neptune,         |
                         sparkAnalytics)  DynamoDB, S3 Vectors,|
                                          SigV4, embedding     |
                                          queue/batch writer)  |
                                            |                  |
                                            v                  |
                                           rag  <--------------+
                                   (QueryPlanner, ResearcherResolver,
                                    CollaborationMetrics, PathFinder,
                                    CommunityAnalyst, SemanticContext,
                                    EvidenceBuilder, AnswerSynthesis,
                                    ClaimVerification, RuntimeWorkflow)
                                            |
                                            v
                                        services  (Routes, IdempotencyStore,
                                                   RuntimeComposer, ScholarChainServer)
```

Rules from `build.sbt`:

- Hadoop and Spark are `Provided`.
- `packageJobs` builds `hadoop/assembly` and `sparkAnalytics/assembly`.
- `dockerLocal` builds the services image on `eclipse-temurin:21.0.5_11-jre-jammy` as non-root `appuser`.

## 3. Runtime data flow

### 3a. Batch pipeline (local today; EMR as defined by Terraform, NOT YET RUN)

```text
 datasets/raw/dblp/snapshot=<date>/dblp.xml.gz   (gitignored; datasets/scripts/download-dblp.sh)
        | datasets/scripts/dblp_select.py
        v
 datasets/derived/dblp/selection-{10k,50k}.xml   (or committed datasets/samples/dblp/dblp-sample-50.xml)
        |                                         EMR: s3://<prefix>-raw/dblp/dblp.xml
        v
 +--------------------------- Hadoop MapReduce (HadoopJobDriver) ---------------------------+
 | J01 dblp-extraction -> j01-work_records (+ _rejects/)                                    |
 |   +-> J06 team-extraction -> j06-teams (PublicationTeam + author-paper lines)            |
 |   |     +-> J07 pair-expansion -> j07-pair_candidates (CoauthorPairCandidate)            |
 |   +-> J03 identifier-extraction -> j03-identifiers                                       |
 |   +-> J04 blocking -> j04-blocks -> J05 feature-construction -> j05-features             |
 | J08 graph-export (J01,J06,J07) -> export/{work_records,teams,pair_candidates,author_paper}|
 | ExportVerifier export/  (exit 0 = valid)                                                 |
 +-------------------------------------------+----------------------------------------------+
                                             | export/work_records
                                             v
 +---------------- EntityResolutionRunner (mention policy er-mention-v1-uncalibrated) -------+
 | --input-run-id <hadoop run id>  -> er/match-decisions/part-00000, review-queue.json,      |
 |                                    RUN-SUMMARY.json   (LLM adjudication disabled)         |
 +-------------------------------------------+----------------------------------------------+
                                             | export/{pair_candidates,teams,work_records}
                                             | + er/match-decisions
                                             v
 +---------------------------- ScholarChainPipeline (Spark/GraphX) -------------------------+
 | DataLoader -> CanonicalGraphBuilder -> CollaborationMetrics -> ConnectedComponents        |
 | -> CommunityDetection (LPA) -> BridgeAnalysis -> KCore -> ShortestPath -> Temporal        |
 | -> SemanticContext -> GraphExportWriter + NeptuneBulkLoadExport                          |
 +--------+--------------------+-------------------+--------------------+--------------------+
          v                    v                   v                    v
   neptune/ (openCypher   metrics/ (Parquet)  summaries/ (Parquet)  edges/ paths/ components/
   CSV nodes+relations)   manifest target:    manifest target:      communities/ temporal/
   target: Neptune        DynamoDB            S3 Vectors            (Parquet, S3)
                                                                    manifest/ run-stats/
```

On EMR, [run-bounded-pipeline.sh](../scripts/run-bounded-pipeline.sh) addresses these locations:

- **Hadoop:** `s3://<prefix>-curated/<run>/hadoop/{stages/…,export}`
- **Entity resolution:** `…/er`
- **Spark:** `s3://<prefix>-graph/<run>/spark`, with checkpoints in `s3://<prefix>-graph/<run>/checkpoints`

Here `<prefix>` is `scholarchain-<env>`. The output-to-store targets (Neptune, DynamoDB, S3 Vectors, S3) are recorded in the Spark manifest written by [GraphExportWriter.scala](../modules/spark/src/main/scala/edu/scholarchain/spark/GraphExportWriter.scala). Loading them into the stores is the `storage` module's job. That loading is **NOT YET RUN** against AWS.

### 3b. Serving path (AWS resources defined in Terraform, NOT DEPLOYED)

```text
  client --HTTP/80 (only ALB_INGRESS_CIDRS)--> ALB (aws_lb)
     | /v1/query* /v1/jobs* /v1/explain* /v1/evidence* /healthz /readyz   (lb_cookie sticky)
     |       -> ECS Fargate service query-api
     | /v1/graph*  -> ECS Fargate service graph-retriever
     |
     |  other ECS services (no ALB route): vector-retriever, synthesis-worker,
     |  verification-worker, embedding-worker (SQS consumer)
     v
  services module (Routes) -> rag RuntimeWorkflow -> storage adapters
     |                                   |
     |                                   +--> Amazon Neptune (serverless capacity 1-4 NCU in dev)
     |                                   +--> Amazon S3 Vectors (vector bucket + indexes, SSE-S3)
     |                                   +--> Amazon DynamoDB: run-ledger, entity-link-cache,
     |                                   |      query-session, evidence-bundle, artifact-registry
     |                                   +--> Amazon S3: raw, curated, graph, rag, artifacts, logs
     |                                   |      (aws:kms, AWS-managed key)
     |                                   +--> Amazon Bedrock InvokeModel
     |                                          (embedding model in tfvars: amazon.titan-embed-text-v2:0)
     v
  AWS Step Functions "<prefix>-query-workflow" (ecs:runTask.sync, starts at Plan)
  Amazon SQS: <prefix>-embedding-work-queue, <prefix>-dead-letter-queue
  Amazon EMR (release emr-7.0.0 in dev.tfvars) for the batch pipeline
  Amazon ECR: six repositories (five services + embedding-worker)
  CloudWatch: log groups /scholarchain/<env>/*, dashboard, alarms; Neptune audit log
  IAM: per-role policies; secretsmanager:GetSecretValue on scholarchain/<env>/*
  Network: VPC, public / private-app / private-data subnets, NAT, VPC endpoints
           (S3, DynamoDB, ECR api/dkr, CloudWatch Logs)
```

Terraform modules under [infra/terraform/modules/](../infra/terraform/main.tf):

- `network`
- `iam`
- `storage`
- `observability`
- `emr`
- `neptune`
- `ecs`
- `workflow`

Terraform does **not** define the following:

- no `aws_kms_key` resource (there is no customer-managed KMS key);
- no `aws_secretsmanager_*` resource;
- no Bedrock resource (only IAM `bedrock:InvokeModel` grants).

When run locally, `ScholarChainServer` composes **stub** workflow and graph components (`RuntimeComposer.composeStubs()`). The diagram above is therefore the target design. The local server does not reach any of these stores.
