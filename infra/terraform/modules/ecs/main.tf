################################################################################
# Locals
################################################################################

locals {
  prefix = "${var.project_name}-${var.environment}"

  services = [
    "query-api",
    "graph-retriever",
    "vector-retriever",
    "synthesis-worker",
    "verification-worker",
  ]

  # Per-service minimum (= desired) task count: var.service_min_tasks entries
  # override var.min_tasks. Dev parks every service except query-api at 0.
  service_min_tasks = { for s in local.services : s => lookup(var.service_min_tasks, s, var.min_tasks) }

  # Path-based routing (documentation of the listener rules below).
  # N19: every route whose handler reads per-process state written by
  # POST /v1/query goes to query-api, the service that ran the query:
  #   Routes.scala queryResults     -> GET /v1/evidence/<id>
  #   Routes.scala jobStore         -> GET /v1/jobs/<id>
  #   Routes.scala idempotencyCache -> POST /v1/query (Idempotency-Key)
  #   auditStore (explain)          -> GET /v1/explain/<queryId>
  # and the query-api target group has lb_cookie stickiness, so with
  # ecs_min_tasks > 1 (prod) a client that keeps the AWSALB cookie stays on
  # the task holding its results. This is a routing mitigation only; the
  # durable fix (shared state, e.g. the DynamoDB evidence-bundle table) is
  # service-engineer's and is still open.
  routing_rules = {
    "query-api" = {
      paths    = ["/v1/query*", "/v1/jobs*", "/v1/explain*", "/healthz", "/readyz", "/v1/evidence*"]
      priority = [100, 101, 102, 103, 104, 105]
    }
    "graph-retriever" = {
      paths    = ["/v1/graph*"]
      priority = [200]
    }
  }

  # Target groups with lb_cookie stickiness (N19).
  sticky_services = ["query-api"]

  # Services that receive ALB traffic: exactly the services named by a
  # listener rule below. Only these get a target group and a load_balancer
  # block. ECS rejects a service whose target group is not attached to a
  # load balancer through a listener, and vector-retriever,
  # verification-worker and (after N19) synthesis-worker have no route:
  # Routes.scala serves no /v1/vectors* or /v1/verify* path, and
  # /v1/evidence* now goes to query-api. Those three services still run
  # (desired_count = min_tasks) with only their container health check; they
  # receive no ALB traffic.
  alb_services = keys(local.routing_rules)

  # Container environment shared by the query-path services and the
  # embedding worker. Every SCHOLARCHAIN_* name below is a ${?...} override
  # that the application actually reads (verified 2026-11-22):
  #   modules/core/src/main/resources/reference.conf
  #     :4  SCHOLARCHAIN_S3_RAW_BUCKET           :6  SCHOLARCHAIN_S3_PROCESSED_BUCKET
  #     :8  SCHOLARCHAIN_S3_VECTORS_BUCKET       :13 SCHOLARCHAIN_NEPTUNE_ENDPOINT
  #     :15 SCHOLARCHAIN_NEPTUNE_PORT            :17 SCHOLARCHAIN_NEPTUNE_REGION
  #     :28 SCHOLARCHAIN_S3_VECTORS_DIMENSIONS   :30 SCHOLARCHAIN_S3_VECTORS_DISTANCE_METRIC
  #     :35 SCHOLARCHAIN_S3_VECTORS_EMBEDDING_MODEL_ID
  #     :60 SCHOLARCHAIN_EMBEDDING_MODEL_ID      :64 SCHOLARCHAIN_MODELS_REGION
  #   modules/storage/src/main/resources/reference.conf
  #     :5  SCHOLARCHAIN_S3_RAW_BUCKET           :7  SCHOLARCHAIN_S3_CURATED_BUCKET
  #     :9  SCHOLARCHAIN_S3_GRAPH_BUCKET         :11 SCHOLARCHAIN_S3_RAG_BUCKET
  #     :16 SCHOLARCHAIN_NEPTUNE_ENDPOINT        :18 SCHOLARCHAIN_NEPTUNE_PORT
  #     :20 SCHOLARCHAIN_NEPTUNE_REGION          :27 SCHOLARCHAIN_DYNAMODB_REGION
  #     :29 SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX   :34 SCHOLARCHAIN_S3_VECTORS_BUCKET
  #     :36 SCHOLARCHAIN_S3_VECTORS_REGION       :49 SCHOLARCHAIN_EMBEDDING_QUEUE_URL (worker only)
  # SCHOLARCHAIN_NEPTUNE_REGION is also the SigV4 signing region (service
  # neptune-db) for https://<endpoint>:<port>/openCypher, so it must be the
  # cluster's region (var.aws_region). SCHOLARCHAIN_S3_VECTORS_BATCH_SIZE
  # (storage :38) is left at the app default.
  # SCHOLARCHAIN_S3_PROCESSED_BUCKET (core) has no "processed" bucket in this
  # configuration; it is pointed at the curated bucket so the app never falls
  # back to the global name "scholarchain-processed".
  # The query-time model id and the index-populating model id come from the
  # same variable, so a query embedding always uses the model that built the
  # index.
  shared_environment = [
    { name = "ENVIRONMENT", value = var.environment },
    { name = "AWS_REGION", value = var.aws_region },
    { name = "SCHOLARCHAIN_S3_RAW_BUCKET", value = var.s3_bucket_names["raw"] },
    { name = "SCHOLARCHAIN_S3_CURATED_BUCKET", value = var.s3_bucket_names["curated"] },
    { name = "SCHOLARCHAIN_S3_PROCESSED_BUCKET", value = var.s3_bucket_names["curated"] },
    { name = "SCHOLARCHAIN_S3_GRAPH_BUCKET", value = var.s3_bucket_names["graph"] },
    { name = "SCHOLARCHAIN_S3_RAG_BUCKET", value = var.s3_bucket_names["rag"] },
    { name = "SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX", value = var.dynamodb_table_prefix },
    { name = "SCHOLARCHAIN_DYNAMODB_REGION", value = var.aws_region },
    { name = "SCHOLARCHAIN_NEPTUNE_ENDPOINT", value = var.neptune_endpoint },
    { name = "SCHOLARCHAIN_NEPTUNE_PORT", value = tostring(var.neptune_port) },
    { name = "SCHOLARCHAIN_NEPTUNE_REGION", value = var.aws_region },
    { name = "SCHOLARCHAIN_MODELS_REGION", value = var.aws_region },
    { name = "SCHOLARCHAIN_S3_VECTORS_BUCKET", value = var.vector_bucket_name },
    { name = "SCHOLARCHAIN_S3_VECTORS_REGION", value = var.aws_region },
    { name = "SCHOLARCHAIN_S3_VECTORS_DIMENSIONS", value = tostring(var.vector_dimension) },
    { name = "SCHOLARCHAIN_S3_VECTORS_DISTANCE_METRIC", value = var.vector_distance_metric },
    { name = "SCHOLARCHAIN_S3_VECTORS_EMBEDDING_MODEL_ID", value = var.embedding_model_id },
    { name = "SCHOLARCHAIN_EMBEDDING_MODEL_ID", value = var.embedding_model_id },
  ]

  # Embedding-worker only. SCHOLARCHAIN_EMBEDDING_QUEUE_URL is read at
  # modules/storage/src/main/resources/reference.conf:49 (no default; blank
  # means the queue manager refuses to start). No query-path service
  # enqueues embedding jobs (no caller of EmbeddingQueueManager.enqueue exists
  # under modules/*/src/main), so the URL is not given to them.
  embedding_worker_environment = [
    { name = "SCHOLARCHAIN_EMBEDDING_QUEUE_URL", value = var.embedding_queue_url },
  ]

  embedding_worker = "embedding-worker"

  embedding_worker_main_class = "edu.scholarchain.services.EmbeddingWorker"
}

################################################################################
# ECR Repositories
#
# IMMUTABLE tags (R14): task definitions reference ":<image_tag>", the full
# Git commit SHA, never ":latest", so every build pushes a new tag and nothing
# is overwritten. No force_delete: a repository that still holds images is
# not deleted by a destroy run (it fails with RepositoryNotEmpty).
# scripts/cleanup.sh therefore inventories every repository's images into the
# evidence archive and, only after a separate typed EMPTY confirmation,
# deletes exactly the inventoried images and re-counts them BEFORE applying
# its (already built and reviewed) destroy plan; it stops before the destroy
# if any image remains, and lists surviving repositories afterwards.
################################################################################

resource "aws_ecr_repository" "services" {
  for_each             = toset(local.services)
  name                 = "${local.prefix}-${each.key}"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = {
    Name    = "${local.prefix}-${each.key}"
    Service = each.key
  }
}

# N7: lifecycle policies never expire a TAGGED image. Every task definition
# references ":<full git sha>" and rollback.sh rolls back to the previous
# task-definition revision (an older sha), so any tagged image may be running
# or be the rollback target; a count-based rule over tagged images (the old
# "any, keep 20") could expire it and break the next task replacement or
# rollback with an image-pull failure. Tags are IMMUTABLE and one tag is
# pushed per deployed commit, so growth is linear in deployments and is
# bounded by cleanup.sh's empty phase. Only UNTAGGED images (failed/partial
# pushes; tags cannot be moved, so nothing running is ever untagged) are
# expired, 14 days after push. NOTE: build single-platform images; an
# untagged child manifest of a multi-arch index would match this rule.
locals {
  ecr_lifecycle_policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Expire untagged images 14 days after push; tagged images are never expired"
      selection = {
        tagStatus   = "untagged"
        countType   = "sinceImagePushed"
        countUnit   = "days"
        countNumber = 14
      }
      action = {
        type = "expire"
      }
    }]
  })
}

resource "aws_ecr_lifecycle_policy" "services" {
  for_each   = toset(local.services)
  repository = aws_ecr_repository.services[each.key].name
  policy     = local.ecr_lifecycle_policy
}

################################################################################
# ECS Cluster
################################################################################

resource "aws_ecs_cluster" "main" {
  name = "${local.prefix}-cluster"

  # Container Insights bills custom metrics per task; var.container_insights
  # = false (dev) turns it off. The basic AWS/ECS metrics used by the alarms
  # and autoscaling are unaffected.
  setting {
    name  = "containerInsights"
    value = var.container_insights ? "enabled" : "disabled"
  }

  tags = {
    Name = "${local.prefix}-cluster"
  }
}

# FARGATE and FARGATE_SPOT are both associated so the batch pipeline
# (modules/batch_fargate, aws ecs run-task --capacity-provider-strategy) can
# prefer Spot and fall back to on-demand. No default strategy is set: the
# services keep launch_type = "FARGATE" and are unaffected.
resource "aws_ecs_cluster_capacity_providers" "main" {
  cluster_name       = aws_ecs_cluster.main.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]
}

################################################################################
# Service Discovery Namespace
################################################################################

resource "aws_service_discovery_private_dns_namespace" "main" {
  name = "scholarchain.local"
  vpc  = var.vpc_id

  tags = {
    Name = "${local.prefix}-service-discovery"
  }
}

resource "aws_service_discovery_service" "services" {
  for_each = toset(local.services)
  name     = each.key

  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.main.id

    dns_records {
      type = "A"
      ttl  = 10
    }

    routing_policy = "MULTIVALUE"
  }

  # failure_threshold is deprecated in provider 6.x (AWS always uses 1).
  health_check_custom_config {}
}

################################################################################
# CloudWatch Log Groups (per service)
################################################################################

resource "aws_cloudwatch_log_group" "services" {
  for_each          = toset(local.services)
  name              = "/scholarchain/${var.environment}/${each.key}"
  retention_in_days = var.log_retention_days

  tags = {
    Name    = "${local.prefix}-${each.key}-logs"
    Service = each.key
  }
}

################################################################################
# Task Definitions
################################################################################

resource "aws_ecs_task_definition" "services" {
  for_each                 = toset(local.services)
  family                   = "${local.prefix}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.ecs_task_execution_role_arn
  task_role_arn            = var.ecs_task_runtime_role_arn

  container_definitions = jsonencode([{
    name      = each.key
    image     = "${aws_ecr_repository.services[each.key].repository_url}:${var.image_tag}"
    essential = true

    portMappings = [{
      containerPort = 8080
      protocol      = "tcp"
    }]

    environment = concat(
      [{ name = "SERVICE_NAME", value = each.key }],
      local.shared_environment,
    )

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = "/scholarchain/${var.environment}/${each.key}"
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "curl -f http://localhost:8080/healthz || exit 1"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }
  }])

  tags = {
    Name    = "${local.prefix}-${each.key}"
    Service = each.key
  }
}

################################################################################
# ALB
################################################################################

resource "aws_lb" "main" {
  name               = "${local.prefix}-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [var.alb_security_group_id]
  subnets            = var.public_subnet_ids

  # AWS-0052: drop headers that are not valid HTTP header names instead of
  # forwarding them to the tasks (request-smuggling hardening).
  drop_invalid_header_fields = true

  tags = {
    Name = "${local.prefix}-alb"
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "application/json"
      message_body = "{\"error\":\"not_found\"}"
      status_code  = "404"
    }
  }

  tags = {
    Name = "${local.prefix}-http-listener"
  }
}

################################################################################
# Target Groups
################################################################################

resource "aws_lb_target_group" "services" {
  for_each    = toset(local.alb_services)
  name        = "${var.environment}-${each.key}"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  # N19: duration-based (ALB-generated AWSALB cookie) stickiness on query-api
  # only, so evidence/explain/jobs/idempotent reads reach the task that
  # served /v1/query. Disabled elsewhere (stateless routes).
  stickiness {
    type            = "lb_cookie"
    enabled         = contains(local.sticky_services, each.key)
    cookie_duration = 3600
  }

  health_check {
    path                = "/healthz"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    timeout             = 5
    interval            = 30
    matcher             = "200"
  }

  tags = {
    Name    = "${local.prefix}-${each.key}-tg"
    Service = each.key
  }

  lifecycle {
    create_before_destroy = true
  }
}

################################################################################
# Listener Rules (path-based routing)
################################################################################

resource "aws_lb_listener_rule" "query_api_query" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 100

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.services["query-api"].arn
  }

  condition {
    path_pattern {
      values = ["/v1/query*"]
    }
  }
}

resource "aws_lb_listener_rule" "query_api_jobs" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 101

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.services["query-api"].arn
  }

  condition {
    path_pattern {
      values = ["/v1/jobs*"]
    }
  }
}

resource "aws_lb_listener_rule" "query_api_explain" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 102

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.services["query-api"].arn
  }

  condition {
    path_pattern {
      values = ["/v1/explain*"]
    }
  }
}

resource "aws_lb_listener_rule" "healthz" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 103

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.services["query-api"].arn
  }

  condition {
    path_pattern {
      values = ["/healthz"]
    }
  }
}

resource "aws_lb_listener_rule" "readyz" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 104

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.services["query-api"].arn
  }

  condition {
    path_pattern {
      values = ["/readyz"]
    }
  }
}

resource "aws_lb_listener_rule" "graph" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 200

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.services["graph-retriever"].arn
  }

  condition {
    path_pattern {
      values = ["/v1/graph*"]
    }
  }
}

# N19: was synthesis-worker (priority 400), which never holds the
# in-process queryResults that GET /v1/evidence/<id> reads, so every lookup
# returned 404. Now query-api, with stickiness (see routing_rules above).
resource "aws_lb_listener_rule" "evidence" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 105

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.services["query-api"].arn
  }

  condition {
    path_pattern {
      values = ["/v1/evidence*"]
    }
  }
}

################################################################################
# ECS Services
################################################################################

resource "aws_ecs_service" "services" {
  for_each        = toset(local.services)
  name            = "${local.prefix}-${each.key}"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.services[each.key].arn
  desired_count   = local.service_min_tasks[each.key]
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = var.app_subnet_ids
    security_groups = [var.app_security_group_id]
  }

  # Only routed services are registered with the ALB (see local.alb_services).
  dynamic "load_balancer" {
    for_each = contains(local.alb_services, each.key) ? [each.key] : []
    content {
      target_group_arn = aws_lb_target_group.services[load_balancer.value].arn
      container_name   = each.key
      container_port   = 8080
    }
  }

  service_registries {
    registry_arn = aws_service_discovery_service.services[each.key].arn
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  tags = {
    Name    = "${local.prefix}-${each.key}"
    Service = each.key
  }

  depends_on = [aws_lb_listener.http]
}

################################################################################
# Autoscaling
################################################################################

resource "aws_appautoscaling_target" "services" {
  for_each           = toset(local.services)
  max_capacity       = max(var.max_tasks, local.service_min_tasks[each.key])
  min_capacity       = local.service_min_tasks[each.key]
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.services[each.key].name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "cpu" {
  for_each           = toset(local.services)
  name               = "${local.prefix}-${each.key}-cpu-scaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.services[each.key].resource_id
  scalable_dimension = aws_appautoscaling_target.services[each.key].scalable_dimension
  service_namespace  = aws_appautoscaling_target.services[each.key].service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    target_value       = var.cpu_target
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}

################################################################################
# Embedding Worker (S3 Vectors writer, parked at zero tasks)
#
# Runs the storage EmbeddingQueueManager / embedding batch writer. It is the
# only task that uses the loader role (s3vectors:PutVectors on the five index
# ARNs, bedrock:InvokeModel on the one approved Titan ARN, and the embedding
# work queue). The five query-path services above keep the read-only runtime
# role.
#
# No load balancer, target group or port mapping: the worker serves no HTTP
# traffic. It starts at 0 tasks and is scaled only by the SQS-backlog step
# scaling defined after the service (0..embedding_worker_max_tasks), so no
# Fargate task runs and no compute is billed while the queue is empty.
# The ECR repository and log group are required for the task definition to be
# runnable at all; both are free while empty.
################################################################################

resource "aws_ecr_repository" "embedding_worker" {
  name                 = "${local.prefix}-${local.embedding_worker}"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = {
    Name    = "${local.prefix}-${local.embedding_worker}"
    Service = local.embedding_worker
  }
}

resource "aws_ecr_lifecycle_policy" "embedding_worker" {
  repository = aws_ecr_repository.embedding_worker.name
  policy     = local.ecr_lifecycle_policy # N7: untagged only; see above
}

resource "aws_cloudwatch_log_group" "embedding_worker" {
  name              = "/scholarchain/${var.environment}/${local.embedding_worker}"
  retention_in_days = var.log_retention_days

  tags = {
    Name    = "${local.prefix}-${local.embedding_worker}-logs"
    Service = local.embedding_worker
  }
}

resource "aws_ecs_task_definition" "embedding_worker" {
  family                   = "${local.prefix}-${local.embedding_worker}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = var.ecs_task_execution_role_arn
  task_role_arn            = var.ecs_task_loader_role_arn

  container_definitions = jsonencode([{
    name      = local.embedding_worker
    image     = "${aws_ecr_repository.embedding_worker.repository_url}:${var.image_tag}"
    essential = true

    # N42: run the worker main class, not the HTTP service main. The image
    # ENTRYPOINT must be a JVM launcher that takes the main class as its
    # first argument (build-engineer packaging follow-up).
    # N69: the services image's entrypoint wrapper accepts "-main <class>"
    # (a bare class name is also mapped to it); the explicit form is used.
    command = ["-main", local.embedding_worker_main_class]

    environment = concat(
      [{ name = "SERVICE_NAME", value = local.embedding_worker }],
      local.shared_environment,
      local.embedding_worker_environment,
    )

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.embedding_worker.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }
  }])

  tags = {
    Name    = "${local.prefix}-${local.embedding_worker}"
    Service = local.embedding_worker
  }
}

resource "aws_ecs_service" "embedding_worker" {
  name            = "${local.prefix}-${local.embedding_worker}"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.embedding_worker.arn
  desired_count   = 0
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = var.app_subnet_ids
    security_groups = [var.app_security_group_id]
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # desired_count is owned by the SQS-backlog step scaling below after creation.
  lifecycle {
    ignore_changes = [desired_count]
  }

  tags = {
    Name    = "${local.prefix}-${local.embedding_worker}"
    Service = local.embedding_worker
  }
}

################################################################################
# Embedding worker elasticity: step scaling on the SQS backlog (README scaling
# table: "ECS target tracking or step scaling on SQS backlog per task").
# Scale-out: +1 task at >= 1 visible message, +2 at >= 500, +3 at >= 2000,
# bounded by var.embedding_worker_max_tasks. Scale-in: back to 0 tasks after
# the queue has been empty for 5 consecutive minutes, so an idle worker costs
# nothing.
################################################################################

locals {
  embedding_queue_name = reverse(split("/", var.embedding_queue_url))[0]
}

resource "aws_appautoscaling_target" "embedding_worker" {
  min_capacity       = 0
  max_capacity       = var.embedding_worker_max_tasks
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.embedding_worker.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "embedding_worker_out" {
  name               = "${local.prefix}-${local.embedding_worker}-backlog-out"
  policy_type        = "StepScaling"
  resource_id        = aws_appautoscaling_target.embedding_worker.resource_id
  scalable_dimension = aws_appautoscaling_target.embedding_worker.scalable_dimension
  service_namespace  = aws_appautoscaling_target.embedding_worker.service_namespace

  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = 60
    metric_aggregation_type = "Maximum"

    step_adjustment {
      metric_interval_lower_bound = 0
      metric_interval_upper_bound = 499
      scaling_adjustment          = 1
    }
    step_adjustment {
      metric_interval_lower_bound = 499
      metric_interval_upper_bound = 1999
      scaling_adjustment          = 2
    }
    step_adjustment {
      metric_interval_lower_bound = 1999
      scaling_adjustment          = 3
    }
  }
}

resource "aws_appautoscaling_policy" "embedding_worker_in" {
  name               = "${local.prefix}-${local.embedding_worker}-backlog-in"
  policy_type        = "StepScaling"
  resource_id        = aws_appautoscaling_target.embedding_worker.resource_id
  scalable_dimension = aws_appautoscaling_target.embedding_worker.scalable_dimension
  service_namespace  = aws_appautoscaling_target.embedding_worker.service_namespace

  step_scaling_policy_configuration {
    adjustment_type         = "ExactCapacity"
    cooldown                = 60
    metric_aggregation_type = "Maximum"

    step_adjustment {
      metric_interval_upper_bound = 0
      scaling_adjustment          = 0
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "embedding_backlog_high" {
  alarm_name          = "${local.prefix}-embedding-backlog-high"
  alarm_description   = "Embedding queue has visible messages: scale the embedding worker out"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = local.embedding_queue_name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_appautoscaling_policy.embedding_worker_out.arn]
}

resource "aws_cloudwatch_metric_alarm" "embedding_backlog_empty" {
  alarm_name          = "${local.prefix}-embedding-backlog-empty"
  alarm_description   = "Embedding queue empty for 5 minutes: scale the embedding worker in to 0"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = local.embedding_queue_name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  threshold           = 0
  comparison_operator = "LessThanOrEqualToThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_appautoscaling_policy.embedding_worker_in.arn]
}
