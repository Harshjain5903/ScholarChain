################################################################################
# Global
################################################################################

variable "aws_region" {
  description = "AWS region for all resources. No default: each environments/<env>.tfvars must set it."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-gov)?-[a-z]+-[0-9]$", var.aws_region))
    error_message = "aws_region must look like an AWS region code, e.g. us-east-2."
  }
}

variable "environment" {
  description = "Deployment environment (dev or prod). Each has its own state key (environments/<env>.backend.hcl)."
  type        = string
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "Environment must be dev or prod (the only environments with a tfvars and backend config)."
  }
}

variable "project_name" {
  description = "Project name used for tagging and naming"
  type        = string
  default     = "scholarchain"
}

################################################################################
# Network
################################################################################

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "availability_zones" {
  description = "List of availability zones (exactly 2, both in var.aws_region). No default: each tfvars must set it."
  type        = list(string)
  validation {
    condition     = length(var.availability_zones) == 2
    error_message = "Exactly 2 availability zones are required."
  }
  validation {
    condition     = alltrue([for az in var.availability_zones : startswith(az, var.aws_region)])
    error_message = "Every availability zone must belong to var.aws_region (e.g. us-east-2a for us-east-2)."
  }
}

# N16 (restrictive option; the human has not yet chosen between restricting
# and accepting a public API). No default and deliberately NOT set in
# environments/*.tfvars: plan fails fast until an operator supplies their own
# CIDR(s), e.g. ALB_INGRESS_CIDRS="203.0.113.10/32" ./scripts/plan.sh dev
# (plan.sh validates and passes it as -var and records it in the plan
# metadata). IPv4 only (the ALB is ipv4). Every entry must be a canonical
# network address; 0.0.0.0/0, ::/0 and any /0 are rejected. N28: the prefix
# length must be at least /16 (at most 65,536 addresses per entry), so a set
# such as 0.0.0.0/1 + 128.0.0.0/1 cannot re-open the ALB to the internet.
# There is no bypass; a wider range needs a reviewed change to this rule.
variable "alb_ingress_cidrs" {
  description = "Operator IPv4 CIDRs allowed to reach the internet-facing ALB on HTTP/80. Required, no default; never 0.0.0.0/0."
  type        = list(string)

  validation {
    condition     = length(var.alb_ingress_cidrs) > 0
    error_message = "alb_ingress_cidrs must list at least one operator CIDR (e.g. [\"203.0.113.10/32\"])."
  }
  validation {
    condition = alltrue([
      for c in var.alb_ingress_cidrs :
      can(regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}/[0-9]{1,2}$", c)) && can(cidrhost(c, 0)) && try(cidrsubnet(c, 0, 0) == c, false)
    ])
    error_message = "Every alb_ingress_cidrs entry must be a valid, canonical IPv4 CIDR (network address with prefix, e.g. 203.0.113.10/32 or 198.51.100.0/24)."
  }
  validation {
    condition = alltrue([
      for c in var.alb_ingress_cidrs :
      !contains(["0.0.0.0/0", "::/0"], c) && !endswith(c, "/0")
    ])
    error_message = "alb_ingress_cidrs must not contain 0.0.0.0/0, ::/0 or any /0 (N16: the ALB must not be open to the internet)."
  }
  validation {
    condition = alltrue([
      for c in var.alb_ingress_cidrs :
      can(tonumber(split("/", c)[1])) && try(tonumber(split("/", c)[1]) >= 16, false)
    ])
    error_message = "Every alb_ingress_cidrs entry must have a prefix length of /16 or longer (e.g. /16 to /32). Wider ranges such as 10.0.0.0/8 or 0.0.0.0/1 are refused (N28: operator allowlist, not the internet); there is no bypass."
  }
}

################################################################################
# EMR
################################################################################

variable "emr_core_instance_type" {
  description = "Instance type for EMR core nodes"
  type        = string
  default     = "m5.xlarge"
}

variable "emr_core_min_nodes" {
  description = "Minimum number of EMR core nodes"
  type        = number
  default     = 2
}

variable "emr_core_max_nodes" {
  description = "Maximum number of EMR core nodes"
  type        = number
  default     = 10
}

variable "emr_task_instance_type" {
  description = "Instance type for EMR task nodes (spot)"
  type        = string
  default     = "m5.xlarge"
}

variable "emr_release_label" {
  description = "EMR release label"
  type        = string
  default     = "emr-7.0.0"
}

################################################################################
# Neptune
################################################################################

variable "neptune_min_capacity" {
  description = "Minimum Neptune serverless NCU"
  type        = number
  default     = 1
}

variable "neptune_max_capacity" {
  description = "Maximum Neptune serverless NCU"
  type        = number
  default     = 8
}

variable "neptune_deletion_protection" {
  description = "Enable deletion protection on Neptune cluster"
  type        = bool
  default     = true
}

################################################################################
# ECS
################################################################################

variable "ecs_cpu" {
  description = "CPU units for ECS tasks (1024 = 1 vCPU)"
  type        = number
  default     = 512
}

variable "ecs_memory" {
  description = "Memory in MiB for ECS tasks"
  type        = number
  default     = 1024
}

variable "ecs_min_tasks" {
  description = "Minimum number of ECS tasks per service"
  type        = number
  default     = 1
}

variable "ecs_service_min_tasks" {
  description = "Per-service override of ecs_min_tasks (desired count and autoscaling minimum), keyed by service name. Dev: every service except query-api is parked at 0."
  type        = map(number)
  default     = {}
}

variable "ecs_container_insights" {
  description = "Enable ECS Container Insights (extra CloudWatch custom-metric cost). Disabled in dev."
  type        = bool
  default     = true
}

variable "enable_interface_endpoints" {
  description = "Create the ecr.api, ecr.dkr and logs interface VPC endpoints. Dev sets false and relies on the NAT gateway (cost); the S3 and DynamoDB gateway endpoints always exist."
  type        = bool
  default     = true
}

variable "ecs_max_tasks" {
  description = "Maximum number of ECS tasks per service"
  type        = number
  default     = 4
}

variable "ecs_cpu_target" {
  description = "CPU utilization target percentage for autoscaling"
  type        = number
  default     = 60
}

variable "image_tag" {
  description = "Immutable container image tag for every ECS task: the full 40-character Git commit SHA the images were built from. No default and no :latest. scripts/plan.sh passes -var image_tag=$(git rev-parse HEAD). The image must already be pushed to each ECR repository with this tag."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{40}$", var.image_tag))
    error_message = "image_tag must be a full 40-character lowercase hex Git commit SHA (no :latest, no mutable tags)."
  }
}

variable "deploy_role_trusted_principal_arns" {
  description = "IAM principal ARNs (users or roles in this account) allowed to assume the deploy role, always with MFA. Empty means the account root principal with an MFA condition (any IAM principal the account grants sts:AssumeRole, and only with MFA)."
  type        = list(string)
  default     = []
}

################################################################################
# S3 Vectors
################################################################################

variable "s3_vectors_bucket_name" {
  description = "S3 Vectors bucket name. null means \"<project>-<environment>-vectors\"."
  type        = string
  default     = null
}

variable "s3_vectors_index_names" {
  description = "S3 Vectors index names. Must be exactly the five names in S3VectorsIndexes.all (modules/storage S3VectorsClient.scala). Indexes have prevent_destroy; see modules/storage/main.tf."
  type        = set(string)
  default = [
    "researcher-profile-v1",
    "collaboration-community-v1",
    "paper-abstract-v1",
    "collaboration-path-v1",
    "topic-institution-summary-v1",
  ]

  validation {
    condition = length(var.s3_vectors_index_names) == 5 && alltrue([
      for n in [
        "researcher-profile-v1",
        "collaboration-community-v1",
        "paper-abstract-v1",
        "collaboration-path-v1",
        "topic-institution-summary-v1",
      ] : contains(var.s3_vectors_index_names, n)
    ])
    error_message = "s3_vectors_index_names must be exactly: researcher-profile-v1, collaboration-community-v1, paper-abstract-v1, collaboration-path-v1, topic-institution-summary-v1."
  }
}

variable "s3_vectors_dimension" {
  description = "Embedding dimension for all S3 Vectors indexes (human decision 2026-11-22: 1024, amazon.titan-embed-text-v2:0). Intentionally no default: every tfvars file must set it explicitly, so a missing value fails plan. Immutable after index creation."
  type        = number

  validation {
    condition     = var.s3_vectors_dimension >= 1 && var.s3_vectors_dimension <= 4096 && floor(var.s3_vectors_dimension) == var.s3_vectors_dimension
    error_message = "s3_vectors_dimension must be an integer between 1 and 4096."
  }
}

variable "s3_vectors_distance_metric" {
  description = "S3 Vectors distance metric (human decision 2026-11-22: cosine). Intentionally no default: every tfvars file must set it explicitly. Immutable after index creation."
  type        = string

  validation {
    condition     = contains(["cosine", "euclidean"], var.s3_vectors_distance_metric)
    error_message = "s3_vectors_distance_metric must be cosine or euclidean."
  }
}

variable "embedding_model_id" {
  description = "Bedrock embedding model id. Exported to ECS tasks as SCHOLARCHAIN_EMBEDDING_MODEL_ID (query-time model) and SCHOLARCHAIN_S3_VECTORS_EMBEDDING_MODEL_ID (index-populating model) so both sides use the same model. Must produce vectors of s3_vectors_dimension."
  type        = string
  default     = "amazon.titan-embed-text-v2:0"

  validation {
    condition     = length(trimspace(var.embedding_model_id)) > 0
    error_message = "embedding_model_id must not be empty."
  }
}

variable "titan_embedding_model_arn" {
  description = "The ONLY Bedrock resource the loader (embedding-worker) task role may invoke, with bedrock:InvokeModel only. Human-approved verbatim (Phase 12 repair-03, R3). Do not change without new human approval. Its region must equal var.aws_region for the grant to match the worker's Bedrock calls; see check \"titan_grant_region\" in main.tf."
  type        = string
  default     = "arn:aws:bedrock:us-east-2::foundation-model/amazon.titan-embed-text-v2:0"

  validation {
    condition     = can(regex("^arn:aws:bedrock:[a-z0-9-]+::foundation-model/amazon\\.titan-embed-text-v2:0$", var.titan_embedding_model_arn))
    error_message = "titan_embedding_model_arn must be a foundation-model ARN for amazon.titan-embed-text-v2:0."
  }
}

variable "s3_vectors_non_filterable_metadata_keys" {
  description = "VectorMetadata keys stored as non-filterable (long pointer fields)"
  type        = list(string)
  default     = ["textS3Uri", "textHash"]
}

################################################################################
# Observability
################################################################################

variable "log_retention_days" {
  description = "CloudWatch log retention in days"
  type        = number
  default     = 30
}

variable "alarm_api_p99_threshold_ms" {
  description = "API p99 latency alarm threshold in milliseconds"
  type        = number
  default     = 10000
}

variable "alarm_neptune_cpu_threshold" {
  description = "Neptune CPU utilization alarm threshold percentage"
  type        = number
  default     = 80
}

################################################################################
# Tags
################################################################################

variable "extra_tags" {
  description = "Additional tags to apply to all resources. Cannot set Project, Environment or ManagedBy (those are merged last and also rejected here)."
  type        = map(string)
  default     = {}

  validation {
    condition     = length(setintersection(toset(keys(var.extra_tags)), toset(["Project", "Environment", "ManagedBy"]))) == 0
    error_message = "extra_tags must not contain Project, Environment or ManagedBy."
  }
}

variable "tag_owner" {
  description = "Optional Owner default tag (team or role, not personal data). Omitted when null."
  type        = string
  default     = null
}

variable "tag_course" {
  description = "Optional Course default tag. Omitted when null."
  type        = string
  default     = null
}

variable "tag_expiration_date" {
  description = "Optional Expiration default tag, YYYY-MM-DD, marking when the environment should be cleaned up. Omitted when null."
  type        = string
  default     = null

  validation {
    condition     = var.tag_expiration_date == null || can(regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$", var.tag_expiration_date))
    error_message = "tag_expiration_date must be null or a YYYY-MM-DD date."
  }
}

################################################################################
# Batch compute (Hadoop + ER + Spark pipeline)
################################################################################

# Dev runs "ecs" (human decision 2026-11-24): the course AWS organization SCP
# explicitly denies elasticmapreduce:* and emr-serverless:*, so an EMR cluster
# cannot be created there, and no instructor approval is needed for the
# alternative. "ecs" runs the SAME assembly jars on ECS Fargate (Hadoop
# MapReduce local runner and Spark local[*], module.emr count 0). EMR stays
# configurable for an account without the SCP. Prod may stay "emr".
variable "batch_compute" {
  description = "Where the Hadoop/ER/Spark pipeline runs: \"emr\" (EMR cluster; module.emr count 1) or \"ecs\" (ECS Fargate tasks running the same assembly jars with the MapReduce local runner and Spark local[*]; module.emr count 0). Dev uses \"ecs\" because the course AWS organization SCP denies EMR (human decision 2026-11-24, no instructor approval required). EMR stays configurable; prod may stay \"emr\". The default is \"emr\"; each tfvars sets its own value."
  type        = string
  default     = "emr"

  validation {
    condition     = contains(["emr", "ecs"], var.batch_compute)
    error_message = "batch_compute must be \"emr\" or \"ecs\"."
  }
}

variable "batch_use_spot" {
  description = "Run the batch Fargate tasks on FARGATE_SPOT with an automatic FARGATE fallback when Spot capacity is unavailable at launch (batch_compute = \"ecs\" only). A Spot interruption after launch fails the stage loudly (it is never silently retried: stage outputs are not idempotent)."
  type        = bool
  default     = true
}

variable "batch_stage_sizes" {
  description = "Fargate CPU units and memory (MiB) per pipeline stage when batch_compute = \"ecs\". Defaults size a 50K-works run: Spark 4 vCPU / 16 GB. CPU is capped at 8 vCPU and memory at 60 GiB (validated in the module) to bound cost."
  type = map(object({
    cpu       = number
    memory_mb = number
  }))
  default = {
    hadoop = { cpu = 2048, memory_mb = 8192 }
    er     = { cpu = 2048, memory_mb = 8192 }
    spark  = { cpu = 4096, memory_mb = 16384 }
  }
}

variable "batch_ephemeral_storage_gib" {
  description = "Fargate ephemeral storage per batch task in GiB (21-200) for staged jars, ER data and local Spark shuffle/checkpoints."
  type        = number
  default     = 100
}

variable "batch_cpu_architecture" {
  description = "CPU architecture of the batch runner image (X86_64 or ARM64)."
  type        = string
  default     = "X86_64"
}

################################################################################
# Storage lifecycle
################################################################################

variable "intermediate_expiration_days" {
  description = "Days after which intermediate pipeline output in the curated bucket (keys under run-*/) is expired, plus noncurrent versions in versioned buckets. N72: default 60, so Hadoop/ER run manifests and summaries (kept under run-*/ in curated) outlive the typical review and evidence window; the pipeline does not copy them elsewhere before expiry."
  type        = number
  default     = 60

  validation {
    condition     = var.intermediate_expiration_days >= 7
    error_message = "intermediate_expiration_days must be at least 7."
  }
}

################################################################################
# Cost guardrails
################################################################################

variable "create_budget" {
  description = "Create the account-wide monthly AWS Budget in this environment. true in dev (set explicitly in dev.tfvars), false in prod: the budget covers the whole account, so a second one would only duplicate notifications. Defaults to false, consistent with scripts/plan.sh. Destroying dev removes the only account-wide budget."
  type        = bool
  default     = false
}

variable "budget_alert_email" {
  description = "Email address that receives the AWS Budgets notifications (ACTUAL 50%, 80%, 100% and FORECASTED 100% of the monthly limit). Required when create_budget is true. Deliberately NO default and never committed: scripts/plan.sh reads it from the BUDGET_ALERT_EMAIL environment variable and passes it through a mode-600 temporary var-file (N75). Sensitive."
  type        = string
  default     = null
  sensitive   = true

  validation {
    condition     = var.budget_alert_email == null || can(regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$", var.budget_alert_email))
    error_message = "budget_alert_email must be a valid email address."
  }
}

variable "budget_limit_usd" {
  description = "Monthly AWS Budgets limit in USD. The budget covers the whole account's unblended cost (no tag filter: cost-allocation tags would have to be activated first, and an unactivated filter silently matches nothing)."
  type        = number
  default     = 25

  validation {
    condition     = var.budget_limit_usd > 0
    error_message = "budget_limit_usd must be positive."
  }
}
