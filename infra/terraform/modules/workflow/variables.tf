variable "project_name" {
  description = "Project name for resource naming"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}

variable "step_functions_role_arn" {
  description = "IAM role ARN for Step Functions"
  type        = string
}

variable "ecs_cluster_arn" {
  description = "ECS cluster ARN"
  type        = string
}

variable "app_subnet_ids" {
  description = "Private app subnet IDs"
  type        = list(string)
}

variable "app_security_group_id" {
  description = "App tier security group ID"
  type        = string
}

variable "audit_queue_url" {
  description = "URL of a dedicated audit SQS queue for the Audit / AuditFailure states. null (the default) means no audit queue exists: those states become Pass states and send nothing. Never pass the embedding work queue here (R23): audit messages are not EmbeddingJob records and would be consumed and dead-lettered by the embedding worker."
  type        = string
  default     = null

  validation {
    condition     = var.audit_queue_url == null || !can(regex("embedding-work-queue$", var.audit_queue_url))
    error_message = "audit_queue_url must not be the embedding work queue."
  }
}

variable "workflow_log_group_arn" {
  description = "CloudWatch log group ARN for workflow logs"
  type        = string
}
