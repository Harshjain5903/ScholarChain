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
