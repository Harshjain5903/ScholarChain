output "run_config" {
  description = "Everything scripts/run-bounded-pipeline.sh needs for `aws ecs run-task` (no secrets)."
  value = {
    cluster_arn        = var.cluster_arn
    container_name     = local.container_name
    subnet_ids         = var.subnet_ids
    security_group_id  = aws_security_group.batch.id
    image_repository   = aws_ecr_repository.runner.name
    image_tag          = var.image_tag
    log_group          = aws_cloudwatch_log_group.batch.name
    task_definitions   = { for k, v in aws_ecs_task_definition.stage : k => v.family }
    task_role_arn      = aws_iam_role.task.arn
    memory_mb          = { for k, v in var.stage_sizes : k => v.memory_mb }
    vcpus              = { for k, v in var.stage_sizes : k => v.cpu / 1024 }
    ephemeral_gib      = var.ephemeral_storage_gib
    capacity_strategy  = var.use_spot ? "spot_with_fargate_fallback" : "fargate"
    allowed_s3_buckets = [for b in concat(local.read_only_buckets, local.read_write_buckets) : var.bucket_arns[b]]
    write_prefix       = "run-*"
  }
}

output "image_repository_name" {
  description = "ECR repository name of the batch runner image"
  value       = aws_ecr_repository.runner.name
}

output "task_role_arn" {
  description = "Batch task role ARN"
  value       = aws_iam_role.task.arn
}
