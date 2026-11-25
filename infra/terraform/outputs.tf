################################################################################
# Network
################################################################################

output "vpc_id" {
  description = "VPC ID"
  value       = module.network.vpc_id
}

output "public_subnet_ids" {
  description = "Public subnet IDs"
  value       = module.network.public_subnet_ids
}

output "private_app_subnet_ids" {
  description = "Private app subnet IDs"
  value       = module.network.private_app_subnet_ids
}

output "private_data_subnet_ids" {
  description = "Private data subnet IDs"
  value       = module.network.private_data_subnet_ids
}

################################################################################
# Storage
################################################################################

output "s3_bucket_names" {
  description = "Map of bucket purpose to name"
  value       = module.storage.bucket_names
}

output "dynamodb_table_names" {
  description = "Map of logical name to DynamoDB table name"
  value       = module.storage.dynamodb_table_names
}

output "s3_vectors_bucket_name" {
  description = "S3 Vectors bucket name"
  value       = module.storage.vector_bucket_name
}

output "s3_vectors_bucket_arn" {
  description = "S3 Vectors bucket ARN"
  value       = module.storage.vector_bucket_arn
}

output "s3_vectors_index_arns" {
  description = "Map of S3 Vectors index name to index ARN"
  value       = module.storage.vector_index_arns
}

################################################################################
# EMR
################################################################################

output "emr_cluster_id" {
  description = "EMR cluster ID (null when batch_compute = \"ecs\")"
  value       = one(module.emr[*].cluster_id)
}

output "emr_master_dns" {
  description = "EMR master public DNS (null when batch_compute = \"ecs\")"
  value       = one(module.emr[*].master_public_dns)
}

output "batch_compute" {
  description = "Where the pipeline runs: emr or ecs. scripts/run-bounded-pipeline.sh selects its path from this verified state output."
  value       = var.batch_compute
}

output "batch_run_config" {
  description = "ECS Fargate batch run configuration (null when batch_compute = \"emr\"); read by scripts/run-bounded-pipeline.sh."
  value       = one(module.batch_fargate[*].run_config)
}

################################################################################
# Neptune
################################################################################

output "neptune_cluster_endpoint" {
  description = "Neptune cluster endpoint"
  value       = module.neptune.cluster_endpoint
}

output "neptune_cluster_resource_id" {
  description = "Neptune cluster resource ID (scope of neptune-db:* IAM grants)"
  value       = module.neptune.cluster_resource_id
}

output "neptune_reader_endpoint" {
  description = "Neptune reader endpoint"
  value       = module.neptune.cluster_reader_endpoint
}

output "neptune_audit_log_group_name" {
  description = "Neptune audit log group (N20); exported by scripts/archive-evidence.sh"
  value       = module.neptune.audit_log_group_name
}

output "neptune_final_snapshot_identifier" {
  description = "Neptune final snapshot identifier taken on destroy (prod; null in dev). Checked by scripts/cleanup.sh (N13)."
  value       = module.neptune.final_snapshot_identifier
}

################################################################################
# ECS
################################################################################

output "ecs_cluster_name" {
  description = "ECS cluster name"
  value       = module.ecs.cluster_name
}

output "alb_dns_name" {
  description = "ALB DNS name"
  value       = module.ecs.alb_dns_name
}

output "ecr_repository_urls" {
  description = "Map of service to ECR repository URL"
  value       = module.ecs.ecr_repository_urls
}

output "embedding_worker_service_name" {
  description = "Embedding worker ECS service name (desired_count 0, loader task role)"
  value       = module.ecs.embedding_worker_service_name
}

output "embedding_worker_ecr_repository_url" {
  description = "Embedding worker ECR repository URL"
  value       = module.ecs.embedding_worker_ecr_repository_url
}

################################################################################
# Workflow
################################################################################

output "state_machine_arn" {
  description = "Step Functions state machine ARN"
  value       = module.workflow.state_machine_arn
}

output "embedding_queue_url" {
  description = "SQS embedding work queue URL"
  value       = module.workflow.embedding_queue_url
}

output "dead_letter_queue_url" {
  description = "SQS dead letter queue URL"
  value       = module.workflow.dead_letter_queue_url
}

################################################################################
# Observability
################################################################################

output "cloudwatch_dashboard_name" {
  description = "CloudWatch dashboard name"
  value       = module.observability.dashboard_name
}

################################################################################
# Environment identity (read by scripts/tf-env.sh and cleanup.sh)
################################################################################

output "environment" {
  description = "Environment this state belongs to"
  value       = var.environment
}

output "aws_region" {
  description = "Deployment region of this state"
  value       = var.aws_region
}

output "dynamodb_table_names_evidence" {
  description = "evidence-bundle and run-ledger table names (exported by scripts/archive-evidence.sh before cleanup)"
  value = {
    evidence_bundle = module.storage.dynamodb_table_names["evidence_bundle"]
    run_ledger      = module.storage.dynamodb_table_names["run_ledger"]
  }
}
