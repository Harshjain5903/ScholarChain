################################################################################
# ScholarChain - Production Environment
################################################################################

# Human decision 2026-11-23: us-east-2 for the initial deployment, retaining
# the Ohio Titan grant (var.titan_embedding_model_arn, us-east-2) for the
# embedding worker. check "titan_grant_region" in main.tf requires the two to
# match.
environment        = "prod"
aws_region         = "us-east-2"
availability_zones = ["us-east-2a", "us-east-2b"]
vpc_cidr           = "10.1.0.0/16"

# N16: alb_ingress_cidrs is intentionally NOT set here (no operator IP is
# committed to Git). plan.sh refuses to run until the operator supplies their
# own CIDR(s), e.g.:
#   ALB_INGRESS_CIDRS="203.0.113.10/32" ./scripts/plan.sh prod
#   ALB_INGRESS_CIDRS="203.0.113.10/32,198.51.100.0/24" ./scripts/plan.sh prod
# (comma-separated canonical IPv4 CIDRs; 0.0.0.0/0, ::/0 and any /0 are
# rejected). The value is embedded in the saved plan and recorded in its
# metadata; deploy.sh applies exactly that plan.

# EMR
# Batch compute: prod may stay "emr" (human decision 2026-11-24); dev uses
# "ecs" because the course organization SCP denies EMR (see dev.tfvars).
batch_compute = "emr"

# The monthly AWS Budget is account-wide and is created by dev only.
create_budget = false

emr_release_label      = "emr-7.0.0"
emr_core_instance_type = "m5.xlarge"
emr_core_min_nodes     = 2
emr_core_max_nodes     = 10
emr_task_instance_type = "m5.xlarge"

# Neptune
neptune_min_capacity        = 2
neptune_max_capacity        = 8
neptune_deletion_protection = true

# ECS
ecs_cpu        = 1024
ecs_memory     = 2048
ecs_min_tasks  = 2
ecs_max_tasks  = 8
ecs_cpu_target = 60

# Observability
log_retention_days          = 90
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
