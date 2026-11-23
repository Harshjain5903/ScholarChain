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

variable "log_retention_days" {
  description = "CloudWatch log retention in days"
  type        = number
}

variable "emr_cluster_id" {
  description = "EMR cluster ID for dashboard metrics"
  type        = string
}

variable "ecs_cluster_name" {
  description = "ECS cluster name for dashboard metrics"
  type        = string
}

variable "ecs_service_names" {
  description = "List of ECS service names"
  type        = list(string)
}

variable "ecs_target_group_arn_suffixes" {
  description = "Map of service name to target group ARN suffix"
  type        = map(string)
  default     = {}
}

variable "neptune_cluster_id" {
  description = "Neptune cluster identifier for metrics"
  type        = string
}

variable "dynamodb_table_names" {
  description = "List of DynamoDB table names for metrics"
  type        = list(string)
}

variable "embedding_queue_name" {
  description = "SQS embedding work queue name"
  type        = string
}

variable "dead_letter_queue_name" {
  description = "SQS dead letter queue name"
  type        = string
}

variable "alb_arn_suffix" {
  description = "ALB ARN suffix for metrics"
  type        = string
}

variable "alarm_api_p99_threshold_ms" {
  description = "API p99 latency alarm threshold in milliseconds"
  type        = number
}

variable "alarm_neptune_cpu_threshold" {
  description = "Neptune CPU utilization alarm threshold percentage"
  type        = number
}
