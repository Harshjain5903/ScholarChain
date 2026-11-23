variable "project_name" {
  description = "Project name for resource naming"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}

variable "s3vectors_index_arns" {
  description = "S3 Vectors index ARNs (from the storage module)"
  type        = list(string)
}

variable "s3_bucket_arns" {
  description = "Map of bucket purpose to bucket ARN (storage module bucket_arns). Every S3 grant is scoped to specific entries of this map (R16)."
  type        = map(string)

  validation {
    condition     = alltrue([for k in ["raw", "curated", "graph", "rag", "artifacts", "logs"] : contains(keys(var.s3_bucket_arns), k)])
    error_message = "s3_bucket_arns must contain raw, curated, graph, rag, artifacts and logs."
  }
}

variable "titan_embedding_model_arn" {
  description = "The single Bedrock foundation-model ARN the loader role may invoke (human-approved, R3). Used verbatim."
  type        = string
}

variable "neptune_cluster_resource_id" {
  description = "Neptune cluster resource ID (neptune module cluster_resource_id) for neptune-db:* resource ARNs"
  type        = string
}

variable "embedding_queue_arn" {
  description = "Embedding work queue ARN (workflow module embedding_queue_arn). Only the loader role may use it."
  type        = string
}

variable "audit_queue_arn" {
  description = "Dedicated audit queue ARN for the Step Functions Audit states. null means no audit queue exists and Step Functions gets no SQS permission (R23)."
  type        = string
  default     = null
}

variable "ecs_cluster_name" {
  description = "ECS cluster name (ecs module cluster_name), used to scope task and service ARNs"
  type        = string
}

variable "ecs_cluster_arn" {
  description = "ECS cluster ARN (ecs module cluster_id), used in ecs:cluster conditions"
  type        = string
}

variable "query_task_definition_families" {
  description = "Task definition families Step Functions may run (ecs module query_task_definition_families values)"
  type        = list(string)
}

variable "deploy_role_trusted_principal_arns" {
  description = "Principals allowed to assume the deploy role (with MFA). Empty means the account root principal, still with MFA required."
  type        = list(string)
  default     = []
}
