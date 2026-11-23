variable "project_name" {
  description = "Project name for resource naming"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}

variable "aws_region" {
  description = "AWS region"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID"
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnet IDs for ALB"
  type        = list(string)
}

variable "app_subnet_ids" {
  description = "Private app subnet IDs for ECS tasks"
  type        = list(string)
}

variable "alb_security_group_id" {
  description = "Security group ID for ALB"
  type        = string
}

variable "app_security_group_id" {
  description = "Security group ID for app tier"
  type        = string
}

variable "ecs_task_execution_role_arn" {
  description = "ECS task execution role ARN"
  type        = string
}

variable "ecs_task_runtime_role_arn" {
  description = "ECS task runtime role ARN (read-only; used by the five query-path services)"
  type        = string
}

variable "ecs_task_loader_role_arn" {
  description = "ECS task loader role ARN (S3 Vectors writer; used only by the embedding-worker task)"
  type        = string
}

variable "cpu" {
  description = "CPU units for ECS tasks"
  type        = number
}

variable "memory" {
  description = "Memory in MiB for ECS tasks"
  type        = number
}

variable "min_tasks" {
  description = "Minimum number of tasks per service"
  type        = number
}

variable "max_tasks" {
  description = "Maximum number of tasks per service"
  type        = number
}

variable "cpu_target" {
  description = "CPU utilization target for autoscaling"
  type        = number
}

variable "vector_bucket_name" {
  description = "S3 Vectors bucket name, exported as SCHOLARCHAIN_S3_VECTORS_BUCKET"
  type        = string
}

variable "vector_dimension" {
  description = "S3 Vectors index dimension, exported as SCHOLARCHAIN_S3_VECTORS_DIMENSIONS"
  type        = number
}

variable "vector_distance_metric" {
  description = "S3 Vectors index distance metric, exported as SCHOLARCHAIN_S3_VECTORS_DISTANCE_METRIC"
  type        = string
}

variable "embedding_model_id" {
  description = "Bedrock embedding model id, exported as SCHOLARCHAIN_EMBEDDING_MODEL_ID and SCHOLARCHAIN_S3_VECTORS_EMBEDDING_MODEL_ID"
  type        = string
}

variable "log_retention_days" {
  description = "CloudWatch log retention in days"
  type        = number
}

variable "image_tag" {
  description = "Immutable image tag (full Git commit SHA) used by every task definition. Never :latest."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{40}$", var.image_tag))
    error_message = "image_tag must be a full 40-character lowercase hex Git commit SHA."
  }
}

variable "s3_bucket_names" {
  description = "Map of bucket purpose to deployed bucket name (storage module bucket_names). raw, curated, graph and rag are exported to tasks."
  type        = map(string)

  validation {
    condition     = alltrue([for k in ["raw", "curated", "graph", "rag"] : contains(keys(var.s3_bucket_names), k)])
    error_message = "s3_bucket_names must contain raw, curated, graph and rag."
  }
}

variable "dynamodb_table_prefix" {
  description = "DynamoDB table prefix (storage module dynamodb_table_prefix), exported as SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX"
  type        = string
}

variable "neptune_endpoint" {
  description = "Neptune cluster endpoint (neptune module cluster_endpoint), exported as SCHOLARCHAIN_NEPTUNE_ENDPOINT"
  type        = string
}

variable "neptune_port" {
  description = "Neptune cluster port (neptune module cluster_port), exported as SCHOLARCHAIN_NEPTUNE_PORT"
  type        = number
}

variable "embedding_queue_url" {
  description = "Embedding work queue URL (workflow module embedding_queue_url), exported ONLY to the embedding-worker as SCHOLARCHAIN_EMBEDDING_QUEUE_URL"
  type        = string
}
