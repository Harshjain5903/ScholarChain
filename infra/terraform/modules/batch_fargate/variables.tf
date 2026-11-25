variable "project_name" {
  description = "Project name for resource naming"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}

variable "aws_region" {
  description = "AWS region (log driver, s3a endpoint region)"
  type        = string
}

variable "cluster_arn" {
  description = "ARN of the existing ECS cluster the batch tasks run in (ecs module cluster_id)"
  type        = string
}

variable "subnet_ids" {
  description = "Private subnet IDs for the batch tasks (no public IP is ever assigned)"
  type        = list(string)
}

variable "vpc_id" {
  description = "VPC ID for the batch tasks' own security group (443 egress only)"
  type        = string
}

variable "use_spot" {
  description = "Prefer FARGATE_SPOT for the batch tasks, falling back to FARGATE when Spot capacity is unavailable at launch."
  type        = bool
  default     = true
}

variable "execution_role_arn" {
  description = "ECS task execution role ARN (pulls the image from ECR, writes logs)"
  type        = string
}

variable "bucket_arns" {
  description = "Map of bucket purpose to bucket ARN (storage module bucket_arns). Only raw, curated, graph and artifacts are used."
  type        = map(string)

  validation {
    condition     = alltrue([for k in ["raw", "curated", "graph", "artifacts"] : contains(keys(var.bucket_arns), k)])
    error_message = "bucket_arns must contain raw, curated, graph and artifacts."
  }
}

variable "image_tag" {
  description = "Immutable image tag (full 40-character Git SHA) of the batch runner image. Never :latest."
  type        = string
}

variable "log_retention_days" {
  description = "CloudWatch log retention in days"
  type        = number
}

variable "stage_sizes" {
  description = "Fargate CPU units (1024 = 1 vCPU) and memory (MiB) per stage: hadoop, er, spark. Must be a valid Fargate combination; CPU is capped at 8192 (8 vCPU) and memory at 61440 MiB to bound cost."
  type = map(object({
    cpu       = number
    memory_mb = number
  }))

  validation {
    condition     = toset(keys(var.stage_sizes)) == toset(["hadoop", "er", "spark"])
    error_message = "stage_sizes must have exactly the keys hadoop, er and spark."
  }

  validation {
    condition = alltrue([
      for k, v in var.stage_sizes : contains([1024, 2048, 4096, 8192], v.cpu)
    ])
    error_message = "Each stage cpu must be 1024, 2048, 4096 or 8192 (Fargate sizes, capped at 8 vCPU)."
  }

  validation {
    condition = alltrue([
      for k, v in var.stage_sizes : (
        v.cpu == 1024 ? (v.memory_mb >= 2048 && v.memory_mb <= 8192 && v.memory_mb % 1024 == 0) :
        v.cpu == 2048 ? (v.memory_mb >= 4096 && v.memory_mb <= 16384 && v.memory_mb % 1024 == 0) :
        v.cpu == 4096 ? (v.memory_mb >= 8192 && v.memory_mb <= 30720 && v.memory_mb % 1024 == 0) :
        v.cpu == 8192 ? (v.memory_mb >= 16384 && v.memory_mb <= 61440 && v.memory_mb % 4096 == 0) :
        false
      )
    ])
    error_message = "Each stage memory_mb must be a valid Fargate memory for its cpu (1024: 2-8 GiB; 2048: 4-16 GiB; 4096: 8-30 GiB; 8192: 16-60 GiB in 4 GiB steps)."
  }
}

variable "ephemeral_storage_gib" {
  description = "Fargate ephemeral storage per task in GiB (21-200). Holds staged jars, ER inputs/outputs and local Spark shuffle/checkpoint data."
  type        = number
  default     = 100

  validation {
    condition     = var.ephemeral_storage_gib >= 21 && var.ephemeral_storage_gib <= 200
    error_message = "ephemeral_storage_gib must be between 21 and 200."
  }
}

variable "cpu_architecture" {
  description = "Fargate CPU architecture; must match the single-platform image that was pushed."
  type        = string
  default     = "X86_64"

  validation {
    condition     = contains(["X86_64", "ARM64"], var.cpu_architecture)
    error_message = "cpu_architecture must be X86_64 or ARM64."
  }
}
