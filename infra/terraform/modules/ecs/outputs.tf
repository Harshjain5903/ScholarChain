output "cluster_id" {
  description = "ECS cluster ID"
  value       = aws_ecs_cluster.main.id
}

output "cluster_name" {
  description = "ECS cluster name"
  value       = aws_ecs_cluster.main.name
}

output "alb_dns_name" {
  description = "ALB DNS name"
  value       = aws_lb.main.dns_name
}

output "alb_arn" {
  description = "ALB ARN"
  value       = aws_lb.main.arn
}

output "ecr_repository_urls" {
  description = "Map of service name to ECR repository URL"
  value       = { for k, v in aws_ecr_repository.services : k => v.repository_url }
}

output "service_names" {
  description = "Map of service name to ECS service name"
  value       = { for k, v in aws_ecs_service.services : k => v.name }
}

output "service_discovery_namespace_id" {
  description = "Service discovery namespace ID"
  value       = aws_service_discovery_private_dns_namespace.main.id
}

output "alb_arn_suffix" {
  description = "ALB ARN suffix for CloudWatch metrics"
  value       = aws_lb.main.arn_suffix
}

output "target_group_arn_suffixes" {
  description = "Map of service name to target group ARN suffix, for the ALB-routed services only (query-api, graph-retriever)"
  value       = { for k, v in aws_lb_target_group.services : k => v.arn_suffix }
}

output "embedding_worker_service_name" {
  description = "Embedding worker ECS service name (desired_count 0)"
  value       = aws_ecs_service.embedding_worker.name
}

output "embedding_worker_task_definition_arn" {
  description = "Embedding worker task definition ARN (task role: loader)"
  value       = aws_ecs_task_definition.embedding_worker.arn
}

output "embedding_worker_ecr_repository_url" {
  description = "Embedding worker ECR repository URL"
  value       = aws_ecr_repository.embedding_worker.repository_url
}

output "query_task_definition_families" {
  description = "Map of query-path service name to task definition family (the families Step Functions runs; RunTask is authorized on task-definition/<family>:<revision>)"
  value       = { for k, v in aws_ecs_task_definition.services : k => v.family }
}

output "embedding_worker_task_definition_family" {
  description = "Embedding worker task definition family"
  value       = aws_ecs_task_definition.embedding_worker.family
}
