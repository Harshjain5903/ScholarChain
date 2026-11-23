################################################################################
# Root Module Composition
#
# The AWS provider (region and default_tags) is configured once, in
# providers.tf. Child modules carry no provider blocks.
################################################################################

################################################################################
# Network
################################################################################

module "network" {
  source             = "./modules/network"
  project_name       = var.project_name
  environment        = var.environment
  aws_region         = var.aws_region
  vpc_cidr           = var.vpc_cidr
  availability_zones = var.availability_zones

  # N16: ALB ingress allowlist (HTTP/80 only).
  alb_ingress_cidrs = var.alb_ingress_cidrs
}

################################################################################
# IAM
################################################################################

module "iam" {
  source       = "./modules/iam"
  project_name = var.project_name
  environment  = var.environment

  s3vectors_index_arns = values(module.storage.vector_index_arns)
  s3_bucket_arns       = module.storage.bucket_arns

  # R3: verbatim, human-approved; loader role only.
  titan_embedding_model_arn = var.titan_embedding_model_arn

  # R5: neptune-db:* resource scoping.
  neptune_cluster_resource_id = module.neptune.cluster_resource_id

  # R6: loader-only SQS on the embedding queue.
  embedding_queue_arn = module.workflow.embedding_queue_arn

  # R23: no audit queue exists in this configuration, so Step Functions gets
  # no SQS permission. Set both this and module.workflow.audit_queue_url
  # once a dedicated audit queue is approved and added.
  audit_queue_arn = null

  # R2 / R17: ECS resource scoping.
  ecs_cluster_name               = module.ecs.cluster_name
  ecs_cluster_arn                = module.ecs.cluster_id
  query_task_definition_families = values(module.ecs.query_task_definition_families)

  deploy_role_trusted_principal_arns = var.deploy_role_trusted_principal_arns
}

################################################################################
# Guard: R3 Bedrock grant region vs deployment region
#
# The loader role may invoke exactly var.titan_embedding_model_arn (human
# approved). A foundation-model ARN is regional, so if its region differs from
# var.aws_region the worker's Bedrock calls (made in var.aws_region, exported
# as SCHOLARCHAIN_MODELS_REGION) will not match the grant and will be denied.
# This is a check block (warning on plan), not a hard failure, so that the
# human-approved grant is never silently rewritten; a human must decide
# whether to re-approve the ARN for var.aws_region or run the worker's
# Bedrock client in the ARN's region.
################################################################################

check "titan_grant_region" {
  assert {
    condition     = split(":", var.titan_embedding_model_arn)[3] == var.aws_region
    error_message = "MANUAL ACTION REQUIRED: titan_embedding_model_arn is in region ${split(":", var.titan_embedding_model_arn)[3]} but aws_region is ${var.aws_region}. The loader role's bedrock:InvokeModel grant will not match Bedrock calls made in ${var.aws_region}. Do not edit the ARN without new human approval."
  }
}

################################################################################
# Storage
################################################################################

module "storage" {
  source       = "./modules/storage"
  project_name = var.project_name
  environment  = var.environment

  vector_bucket_name                  = var.s3_vectors_bucket_name
  vector_index_names                  = var.s3_vectors_index_names
  vector_dimension                    = var.s3_vectors_dimension
  vector_distance_metric              = var.s3_vectors_distance_metric
  vector_non_filterable_metadata_keys = var.s3_vectors_non_filterable_metadata_keys
}

################################################################################
# Observability (created before workflow and ECS so log groups exist)
################################################################################

module "observability" {
  source       = "./modules/observability"
  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region

  log_retention_days = var.log_retention_days

  emr_cluster_id                = module.emr.cluster_id
  ecs_cluster_name              = module.ecs.cluster_name
  ecs_service_names             = values(module.ecs.service_names)
  ecs_target_group_arn_suffixes = module.ecs.target_group_arn_suffixes
  neptune_cluster_id            = module.neptune.cluster_id

  dynamodb_table_names = values(module.storage.dynamodb_table_names)

  embedding_queue_name   = "${var.project_name}-${var.environment}-embedding-work-queue"
  dead_letter_queue_name = "${var.project_name}-${var.environment}-dead-letter-queue"
  alb_arn_suffix         = module.ecs.alb_arn_suffix

  alarm_api_p99_threshold_ms  = var.alarm_api_p99_threshold_ms
  alarm_neptune_cpu_threshold = var.alarm_neptune_cpu_threshold
}

################################################################################
# EMR
################################################################################

module "emr" {
  source       = "./modules/emr"
  project_name = var.project_name
  environment  = var.environment

  release_label      = var.emr_release_label
  core_instance_type = var.emr_core_instance_type
  core_min_nodes     = var.emr_core_min_nodes
  core_max_nodes     = var.emr_core_max_nodes
  task_instance_type = var.emr_task_instance_type

  emr_service_role_arn         = module.iam.emr_service_role_arn
  emr_ec2_instance_profile_arn = module.iam.emr_ec2_instance_profile_arn

  subnet_id                = module.network.private_app_subnet_ids[0]
  master_security_group_id = module.network.emr_master_security_group_id
  core_security_group_id   = module.network.emr_core_security_group_id
  logs_bucket              = module.storage.logs_bucket_name
}

################################################################################
# Neptune
################################################################################

module "neptune" {
  source       = "./modules/neptune"
  project_name = var.project_name
  environment  = var.environment

  subnet_ids        = module.network.private_data_subnet_ids
  security_group_id = module.network.data_security_group_id

  min_capacity        = var.neptune_min_capacity
  max_capacity        = var.neptune_max_capacity
  deletion_protection = var.neptune_deletion_protection

  neptune_loader_role_arn = module.iam.neptune_loader_role_arn

  # N20: retention of /aws/neptune/<cluster-id>/audit.
  log_retention_days = var.log_retention_days
}

################################################################################
# ECS
################################################################################

module "ecs" {
  source       = "./modules/ecs"
  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region

  vpc_id            = module.network.vpc_id
  public_subnet_ids = module.network.public_subnet_ids
  app_subnet_ids    = module.network.private_app_subnet_ids

  alb_security_group_id = module.network.alb_security_group_id
  app_security_group_id = module.network.app_security_group_id

  ecs_task_execution_role_arn = module.iam.ecs_task_execution_role_arn
  ecs_task_runtime_role_arn   = module.iam.ecs_task_runtime_role_arn
  ecs_task_loader_role_arn    = module.iam.ecs_task_loader_role_arn

  cpu        = var.ecs_cpu
  memory     = var.ecs_memory
  min_tasks  = var.ecs_min_tasks
  max_tasks  = var.ecs_max_tasks
  cpu_target = var.ecs_cpu_target

  log_retention_days = var.log_retention_days

  vector_bucket_name     = module.storage.vector_bucket_name
  vector_dimension       = module.storage.vector_dimension
  vector_distance_metric = module.storage.vector_distance_metric
  embedding_model_id     = var.embedding_model_id

  # R14: immutable Git-SHA image tag, never :latest.
  image_tag = var.image_tag

  # R4: application configuration from module outputs.
  s3_bucket_names       = module.storage.bucket_names
  dynamodb_table_prefix = module.storage.dynamodb_table_prefix
  neptune_endpoint      = module.neptune.cluster_endpoint
  neptune_port          = module.neptune.cluster_port
  embedding_queue_url   = module.workflow.embedding_queue_url
}

################################################################################
# Workflow
################################################################################

module "workflow" {
  source       = "./modules/workflow"
  project_name = var.project_name
  environment  = var.environment

  step_functions_role_arn = module.iam.step_functions_role_arn
  ecs_cluster_arn         = module.ecs.cluster_id
  app_subnet_ids          = module.network.private_app_subnet_ids
  app_security_group_id   = module.network.app_security_group_id
  workflow_log_group_arn  = module.observability.workflow_log_group_arn

  # R23: no dedicated audit queue exists. null turns the Audit states into
  # Pass states; the embedding work queue is never used for audit messages.
  audit_queue_url = null
}
