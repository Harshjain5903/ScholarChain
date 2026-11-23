variable "project_name" {
  description = "Project name for resource naming"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}

variable "release_label" {
  description = "EMR release label"
  type        = string
}

variable "core_instance_type" {
  description = "Instance type for core/master nodes"
  type        = string
}

variable "core_min_nodes" {
  description = "Minimum core node count"
  type        = number
}

variable "core_max_nodes" {
  description = "Maximum core node count"
  type        = number
}

variable "task_instance_type" {
  description = "Instance type for task nodes (spot)"
  type        = string
}

variable "emr_service_role_arn" {
  description = "ARN of the EMR service role"
  type        = string
}

variable "emr_ec2_instance_profile_arn" {
  description = "ARN of the EMR EC2 instance profile"
  type        = string
}

variable "subnet_id" {
  description = "Subnet ID for EMR cluster placement"
  type        = string
}

variable "master_security_group_id" {
  description = "Security group ID for EMR master"
  type        = string
}

variable "core_security_group_id" {
  description = "Security group ID for EMR core nodes"
  type        = string
}

variable "logs_bucket" {
  description = "S3 bucket name for EMR logs"
  type        = string
}
