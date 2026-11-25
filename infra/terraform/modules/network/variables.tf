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

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
}

variable "availability_zones" {
  description = "List of availability zones"
  type        = list(string)
}

variable "alb_ingress_cidrs" {
  description = "IPv4 CIDRs allowed to reach the ALB on HTTP/80 (N16). Validated at the root module (non-empty, valid, no /0)."
  type        = list(string)
}

variable "enable_emr" {
  description = "Create the EMR master/core security groups and the EMR-to-Neptune rule (true only when batch_compute = \"emr\"; N77)."
  type        = bool
  default     = true
}

variable "enable_interface_endpoints" {
  description = "Create the ecr.api, ecr.dkr and logs interface VPC endpoints and their security group. false relies on the NAT gateway for those APIs (dev cost reduction). The S3 and DynamoDB gateway endpoints are always created."
  type        = bool
  default     = true
}
