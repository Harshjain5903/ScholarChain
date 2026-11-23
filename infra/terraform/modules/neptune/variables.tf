variable "project_name" {
  description = "Project name for resource naming"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}

variable "subnet_ids" {
  description = "Subnet IDs for Neptune subnet group"
  type        = list(string)
}

variable "security_group_id" {
  description = "Security group ID for Neptune"
  type        = string
}

variable "min_capacity" {
  description = "Minimum Neptune serverless NCU"
  type        = number
}

variable "max_capacity" {
  description = "Maximum Neptune serverless NCU"
  type        = number
}

variable "deletion_protection" {
  description = "Enable deletion protection"
  type        = bool
}

variable "neptune_loader_role_arn" {
  description = "IAM role ARN for Neptune bulk loader"
  type        = string
}

variable "log_retention_days" {
  description = "Retention in days for the Neptune audit log group /aws/neptune/<cluster-id>/audit (N20)"
  type        = number
}
