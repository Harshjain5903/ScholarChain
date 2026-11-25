################################################################################
# Batch compute on ECS Fargate (replaces EMR when var.batch_compute = "ecs")
#
# Why plain ECS RunTask rather than AWS Batch: nothing here needs a queue,
# scheduling or retries (the pipeline script runs three sequential stages and
# fails closed), so Batch would add a compute environment, job queue, service
# role and job definitions for no benefit. Task definitions plus
# scripts/run-bounded-pipeline.sh (aws ecs run-task, bounded wait) is the
# smaller thing to get right. Tasks cost nothing while idle. With var.use_spot
# the script launches on FARGATE_SPOT and falls back to FARGATE only when Spot
# capacity is unavailable at launch (see run_config.capacity_strategy).
#
# One image, three task definition families (hadoop, er, spark) that differ
# only in CPU and memory. The command is supplied per run by the script
# (container override); the entry point is fixed to `bash -c` so an override
# can only be a script, never a different binary. The script stages the SAME
# assembly jars from the artifacts bucket, so no jar is baked into the image.
#
# The image is NOT built by Terraform and is not one of the application images:
# docker/batch-runner/Dockerfile (build-engineer) provides bash, JRE 17, AWS CLI
# v2, Hadoop 3.3.6 with hadoop-aws under
# $HADOOP_HOME/share/hadoop/tools/lib and Spark 3.5.3 with hadoop-aws under
# $SPARK_HOME/jars. The job jars are fetched from the artifacts bucket at run
# time (pinned by S3 VersionId and verified by sha256). The default task scripts
# use the hadoop / spark-submit path of that image; build-engineer's standalone
# (java -jar) assemblies are not used because their jar names and command-line
# contract are not in the tree to verify. The task preflight additionally
# requires GNU coreutils `timeout` and `sha256sum` (N66, N68) and fails closed
# (exit 97) when any tool or jar is missing.
################################################################################

data "aws_caller_identity" "current" {}

locals {
  prefix         = "${var.project_name}-${var.environment}"
  container_name = "runner"
  stages         = toset(["hadoop", "er", "spark"])

  # Role scoping: raw and artifacts are read-only inputs; curated and graph
  # are the pipeline outputs. No logs, rag or vector access.
  read_only_buckets  = ["raw", "artifacts"]
  read_write_buckets = ["curated", "graph"]
}

################################################################################
# Image repository
################################################################################

resource "aws_ecr_repository" "runner" {
  name                 = "${local.prefix}-batch-runner"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = {
    Name    = "${local.prefix}-batch-runner"
    Service = "batch-runner"
  }
}

# Same rule as the service repositories (N7): only UNTAGGED images expire;
# tagged (Git SHA) images are never removed by lifecycle.
resource "aws_ecr_lifecycle_policy" "runner" {
  repository = aws_ecr_repository.runner.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Expire untagged images after 14 days"
      selection = {
        tagStatus   = "untagged"
        countType   = "sinceImagePushed"
        countUnit   = "days"
        countNumber = 14
      }
      action = { type = "expire" }
    }]
  })
}

resource "aws_cloudwatch_log_group" "batch" {
  name              = "/scholarchain/${var.environment}/batch"
  retention_in_days = var.log_retention_days

  tags = {
    Name = "${local.prefix}-batch-logs"
  }
}

################################################################################
# Task role: one role, S3 on raw/curated/graph/artifacts only (N73: run-* writes)
################################################################################

resource "aws_iam_role" "task" {
  name = "${local.prefix}-batch-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "ecs-tasks.amazonaws.com"
      }
      Condition = {
        StringEquals = {
          "aws:SourceAccount" = data.aws_caller_identity.current.account_id
        }
      }
    }]
  })

  tags = { Name = "${local.prefix}-batch-task-role" }
}

resource "aws_iam_role_policy" "task" {
  name = "${local.prefix}-batch-task-policy"
  role = aws_iam_role.task.id

  # N73: writes and deletes are confined to run-* prefixes of curated and graph
  # (the pipeline's run ids are run-<timestamp>-<hex>-<stage>); listing is
  # confined by s3:prefix to the prefixes each bucket is read from. Reads of the
  # artifacts bucket are limited to jars/ (and its object versions, so the
  # task can fetch the exact jar version the operator recorded, N68).
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadRaw"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = "${var.bucket_arns["raw"]}/*"
      },
      {
        Sid      = "ReadJarVersions"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:GetObjectVersion"]
        Resource = "${var.bucket_arns["artifacts"]}/jars/*"
      },
      {
        # s3a renames (copy + delete) and multipart output need these, on the
        # run-* prefixes of the output buckets only.
        Sid    = "ReadWriteRunPrefixes"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:AbortMultipartUpload",
          "s3:ListMultipartUploadParts",
        ]
        Resource = [
          for b in local.read_write_buckets : "${var.bucket_arns[b]}/run-*"
        ]
      },
      {
        Sid      = "ListInputPrefixes"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [var.bucket_arns["raw"]]
        Condition = {
          StringLike = {
            "s3:prefix" = ["dblp", "dblp/*"]
          }
        }
      },
      {
        Sid      = "ListJarPrefix"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [var.bucket_arns["artifacts"]]
        Condition = {
          StringLike = {
            "s3:prefix" = ["jars", "jars/*"]
          }
        }
      },
      {
        Sid    = "ListRunPrefixes"
        Effect = "Allow"
        Action = [
          "s3:ListBucket",
          "s3:ListBucketMultipartUploads",
        ]
        Resource = [for b in local.read_write_buckets : var.bucket_arns[b]]
        Condition = {
          StringLike = {
            "s3:prefix" = ["run-*"]
          }
        }
      },
      {
        Sid      = "BucketLocation"
        Effect   = "Allow"
        Action   = ["s3:GetBucketLocation"]
        Resource = [for b in concat(local.read_only_buckets, local.read_write_buckets) : var.bucket_arns[b]]
      },
    ]
  })
}

################################################################################
# Security group: HTTPS egress only (N73)
#
# The batch tasks need exactly: ECR (image pull, through the NAT when the
# interface endpoints are off), CloudWatch Logs, STS and S3 (gateway endpoint
# or NAT), all on 443. No ingress and no other egress; the app-tier group is no
# longer shared (it also allows Neptune on 8182).
################################################################################

resource "aws_security_group" "batch" {
  name_prefix = "${local.prefix}-batch-"
  description = "ScholarChain batch Fargate tasks: no ingress; egress tcp/443 only"
  vpc_id      = var.vpc_id

  tags = {
    Name = "${local.prefix}-batch-sg"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_egress_rule" "batch_https" {
  security_group_id = aws_security_group.batch.id
  description       = "HTTPS to AWS APIs (S3 gateway endpoint, NAT)"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"

  tags = {
    Name = "${local.prefix}-batch-out-443"
  }
}

################################################################################
# Task definitions (hadoop, er, spark)
################################################################################

resource "aws_ecs_task_definition" "stage" {
  for_each                 = local.stages
  family                   = "${local.prefix}-batch-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.stage_sizes[each.key].cpu
  memory                   = var.stage_sizes[each.key].memory_mb
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = aws_iam_role.task.arn

  ephemeral_storage {
    size_in_gib = var.ephemeral_storage_gib
  }

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([{
    name      = local.container_name
    image     = "${aws_ecr_repository.runner.repository_url}:${var.image_tag}"
    essential = true

    # Fixed entry point; run-bounded-pipeline.sh supplies the script as the
    # command override.
    entryPoint = ["bash", "-c"]
    command    = ["echo 'no pipeline script supplied (run via scripts/run-bounded-pipeline.sh)' >&2; exit 64"]

    environment = [
      { name = "AWS_REGION", value = var.aws_region },
      { name = "AWS_DEFAULT_REGION", value = var.aws_region },
      { name = "SCHOLARCHAIN_STAGE", value = each.key },
      { name = "SCHOLARCHAIN_TASK_MEMORY_MB", value = tostring(var.stage_sizes[each.key].memory_mb) },
      { name = "SCHOLARCHAIN_TASK_VCPUS", value = tostring(var.stage_sizes[each.key].cpu / 1024) },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.batch.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = each.key
      }
    }
  }])

  tags = {
    Name  = "${local.prefix}-batch-${each.key}"
    Stage = each.key
  }
}
