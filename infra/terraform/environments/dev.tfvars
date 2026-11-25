################################################################################
# ScholarChain - Development Environment
#
# DAILY COST ESTIMATE (ESTIMATE, not a quote; us-east-2 list prices from
# memory, no data transfer beyond a few GB, 24 h/day):
#   always on, after the 2026-11-24 reductions:
#     Neptune Serverless, 1 NCU floor     3.86   (the max of 2 NCU only caps bursts)
#     NAT gateway + its public IPv4       1.20
#     ALB + 2 public IPv4 + LCU           0.80
#     query-api (0.5 vCPU / 1 GB, 1 task) 0.59
#     CloudWatch dashboard/alarms/logs    0.16
#     S3, DynamoDB, ECR storage           0.05
#                                         ----
#     steady state                       ~6.7 USD/day  (~200 USD per 30 days)
#   before the reductions                ~10.6 USD/day  (3 interface endpoints
#     1.44, four more always-on services 2.36, Container Insights ~0.10)
#   demo window (all five services up)   ~9.0 USD/day  (+2.36)
#   one pipeline run on Fargate Spot     ~0.1 to 0.5 USD (Spark 4 vCPU / 16 GB
#     about 0.07/h Spot vs 0.23/h on demand; ECR pull through the NAT ~0.05/GB)
# WARNING: the monthly AWS Budget limit is USD 25 (budget_limit_usd). Neptune
# plus NAT plus ALB alone are about 5.9 USD/day idle, so the limit is reached in
# roughly 4 days of a standing environment. Run scripts/cleanup.sh between
# sessions or raise budget_limit_usd (human decision).
################################################################################

# Human decision 2026-11-23: us-east-2 for the initial deployment, retaining
# the Ohio Titan grant (var.titan_embedding_model_arn, us-east-2) for the
# embedding worker. check "titan_grant_region" in main.tf requires the two to
# match.
environment        = "dev"
aws_region         = "us-east-2"
availability_zones = ["us-east-2a", "us-east-2b"]
vpc_cidr           = "10.0.0.0/16"

# N16: alb_ingress_cidrs is intentionally NOT set here (no operator IP is
# committed to Git). plan.sh refuses to run until the operator supplies their
# own CIDR(s), e.g.:
#   ALB_INGRESS_CIDRS="203.0.113.10/32" ./scripts/plan.sh dev
#   ALB_INGRESS_CIDRS="203.0.113.10/32,198.51.100.0/24" ./scripts/plan.sh dev
# (comma-separated canonical IPv4 CIDRs; 0.0.0.0/0, ::/0 and any /0 are
# rejected). The value is embedded in the saved plan and recorded in its
# metadata; deploy.sh applies exactly that plan.

# Batch compute (Hadoop + ER + Spark pipeline).
# Human decision 2026-11-24: dev runs the pipeline on ECS Fargate. The course
# AWS organization SCP explicitly denies ALL elasticmapreduce:* and
# emr-serverless:* actions, so an EMR cluster cannot be created in this
# organization; instructor approval is not required for this alternative.
# Fargate runs the SAME assembly jars: the Hadoop MapReduce local runner
# (mapreduce.framework.name=local, s3a) and Spark --master local[*]. module.emr
# has count 0, and with it the EMR roles, instance profile and security groups
# (N77). EMR stays configurable (batch_compute = "emr") for an account without
# the SCP; prod may stay "emr". scripts/run-bounded-pipeline.sh reads the
# choice from the verified state. This path needs the batch runner image
# (docker/batch-runner) pushed to <prefix>-batch-runner:<git sha>.
batch_compute = "ecs"

# Batch tasks prefer FARGATE_SPOT and fall back to FARGATE when Spot capacity is
# unavailable at launch; ephemeral storage is 40 GiB (jars, ER data, Spark
# scratch for a bounded run).
batch_use_spot              = true
batch_ephemeral_storage_gib = 40

# Fargate sizing per stage when batch_compute = "ecs" (defaults shown). Spark at
# 50K works: 4 vCPU / 16 GB. Hard caps (8 vCPU, 60 GiB) are enforced by validation.
# batch_stage_sizes = {
#   hadoop = { cpu = 2048, memory_mb = 8192 }
#   er     = { cpu = 2048, memory_mb = 8192 }
#   spark  = { cpu = 4096, memory_mb = 16384 }
# }

# Cost guardrails. budget_alert_email is intentionally NOT set here (no
# default, never committed): export BUDGET_ALERT_EMAIL=<address> before
# ./scripts/plan.sh dev. The monthly limit defaults to USD 25 (budget_limit_usd).
# create_budget: true here, false in prod (the budget is account-wide).
create_budget = true

# Intermediate run output in the curated bucket expires after this many days
# (N72: 60, so run manifests and summaries outlive the evidence window).
intermediate_expiration_days = 60

# EMR (unused while batch_compute = "ecs"; kept so "emr" remains configurable)
emr_release_label      = "emr-7.0.0"
emr_core_instance_type = "m5.xlarge"
emr_core_min_nodes     = 2
emr_core_max_nodes     = 5
emr_task_instance_type = "m5.xlarge"

# Neptune
neptune_min_capacity        = 1
neptune_max_capacity        = 2
neptune_deletion_protection = false

# ECS. Cost posture "reductions plus a demo window" (human decision
# 2026-11-24): only query-api runs by default; the other four services are
# parked at 0 and scaled up for the demo window (raise ecs_service_min_tasks for the demo
# window and re-plan). Container Insights is off, and
# the ecr.api / ecr.dkr / logs interface endpoints are not created (the NAT
# gateway carries those APIs; the S3 and DynamoDB gateway endpoints remain).
ecs_cpu        = 512
ecs_memory     = 1024
ecs_min_tasks  = 1
ecs_max_tasks  = 4
ecs_cpu_target = 60
ecs_service_min_tasks = {
  "graph-retriever"     = 0
  "vector-retriever"    = 0
  "synthesis-worker"    = 0
  "verification-worker" = 0
}
ecs_container_insights     = false
enable_interface_endpoints = false

# Observability
log_retention_days          = 30
alarm_api_p99_threshold_ms  = 10000
alarm_neptune_cpu_threshold = 80

# S3 Vectors
# Human decision 2026-11-22: dimension 1024, cosine distance, indexes populated
# by amazon.titan-embed-text-v2:0. Both values are immutable after index
# creation; changing either forces replacement of all five indexes (and loss
# of their vectors). The root variables have no default on purpose.
s3_vectors_dimension       = 1024
s3_vectors_distance_metric = "cosine"
embedding_model_id         = "amazon.titan-embed-text-v2:0"
