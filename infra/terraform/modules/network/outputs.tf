output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.main.id
}

output "vpc_cidr" {
  description = "VPC CIDR block"
  value       = aws_vpc.main.cidr_block
}

output "public_subnet_ids" {
  description = "Public subnet IDs"
  value       = aws_subnet.public[*].id
}

output "private_app_subnet_ids" {
  description = "Private app subnet IDs"
  value       = aws_subnet.private_app[*].id
}

output "private_data_subnet_ids" {
  description = "Private data subnet IDs"
  value       = aws_subnet.private_data[*].id
}

output "alb_security_group_id" {
  description = "ALB security group ID"
  value       = aws_security_group.alb.id
}

output "app_security_group_id" {
  description = "App tier security group ID"
  value       = aws_security_group.app.id
}

output "data_security_group_id" {
  description = "Data tier security group ID"
  value       = aws_security_group.data.id
}

output "emr_master_security_group_id" {
  description = "EMR master security group ID"
  value       = aws_security_group.emr_master.id
}

output "emr_core_security_group_id" {
  description = "EMR core security group ID"
  value       = aws_security_group.emr_core.id
}
