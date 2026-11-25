output "emr_service_role_arn" {
  description = "EMR service role ARN"
  value       = one(aws_iam_role.emr_service[*].arn)
}

output "emr_ec2_instance_profile_arn" {
  description = "EMR EC2 instance profile ARN"
  value       = one(aws_iam_instance_profile.emr_ec2[*].arn)
}

output "ecs_task_execution_role_arn" {
  description = "ECS task execution role ARN"
  value       = aws_iam_role.ecs_task_execution.arn
}

output "ecs_task_runtime_role_arn" {
  description = "ECS task runtime role ARN (read-only)"
  value       = aws_iam_role.ecs_task_runtime.arn
}

output "ecs_task_loader_role_arn" {
  description = "ECS task loader role ARN (read-write)"
  value       = aws_iam_role.ecs_task_loader.arn
}

output "neptune_loader_role_arn" {
  description = "Neptune loader role ARN"
  value       = aws_iam_role.neptune_loader.arn
}

output "step_functions_role_arn" {
  description = "Step Functions role ARN"
  value       = aws_iam_role.step_functions.arn
}

output "deploy_role_arn" {
  description = "Deploy role ARN"
  value       = aws_iam_role.deploy.arn
}
