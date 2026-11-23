################################################################################
# Data Sources
################################################################################

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.region
  prefix     = "${var.project_name}-${var.environment}"

  # R8: secrets are scoped to this environment's path only.
  env_secrets_arn = "arn:aws:secretsmanager:${local.region}:${local.account_id}:secret:scholarchain/${var.environment}/*"

  # R16: S3 grants name specific buckets from the storage module, per role.
  # Each entry expands to the bucket ARN (ListBucket) and its objects.
  s3_resources = {
    for k, arn in var.s3_bucket_arns : k => [arn, "${arn}/*"]
  }

  # R5: IAM database authentication resource for this cluster only.
  neptune_db_arn = "arn:aws:neptune-db:${local.region}:${local.account_id}:${var.neptune_cluster_resource_id}/*"

  ecs_task_arn_pattern    = "arn:aws:ecs:${local.region}:${local.account_id}:task/${var.ecs_cluster_name}/*"
  ecs_service_arn_pattern = "arn:aws:ecs:${local.region}:${local.account_id}:service/${var.ecs_cluster_name}/${local.prefix}-*"
}

################################################################################
# EMR Service Role
################################################################################

resource "aws_iam_role" "emr_service" {
  name = "${local.prefix}-emr-service-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "elasticmapreduce.amazonaws.com"
      }
    }]
  })

  tags = { Name = "${local.prefix}-emr-service-role" }
}

resource "aws_iam_role_policy" "emr_service" {
  name = "${local.prefix}-emr-service-policy"
  role = aws_iam_role.emr_service.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "EC2Management"
        Effect = "Allow"
        Action = [
          "ec2:AuthorizeSecurityGroupEgress",
          "ec2:AuthorizeSecurityGroupIngress",
          "ec2:CancelSpotInstanceRequests",
          "ec2:CreateNetworkInterface",
          "ec2:CreateSecurityGroup",
          "ec2:CreateTags",
          "ec2:DeleteNetworkInterface",
          "ec2:DeleteSecurityGroup",
          "ec2:DeleteTags",
          "ec2:DescribeAvailabilityZones",
          "ec2:DescribeInstances",
          "ec2:DescribeNetworkInterfaces",
          "ec2:DescribeSecurityGroups",
          "ec2:DescribeSubnets",
          "ec2:DescribeVpcs",
          "ec2:ModifyInstanceAttribute",
          "ec2:ModifyNetworkInterfaceAttribute",
          "ec2:RequestSpotInstances",
          "ec2:RevokeSecurityGroupEgress",
          "ec2:RunInstances",
          "ec2:TerminateInstances",
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "aws:RequestedRegion" = local.region
          }
        }
      },
      {
        # EMR writes cluster logs to the logs bucket (log_uri) and reads the
        # bootstrap action from it; job jars come from artifacts.
        Sid    = "S3LogsAndArtifacts"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:ListBucket",
          "s3:PutObject",
        ]
        Resource = concat(local.s3_resources["logs"], local.s3_resources["artifacts"])
      },
      {
        Sid    = "IAMPassRole"
        Effect = "Allow"
        Action = "iam:PassRole"
        Resource = [
          aws_iam_role.emr_ec2.arn,
        ]
      },
    ]
  })
}

################################################################################
# EMR EC2 Role (instance profile)
################################################################################

resource "aws_iam_role" "emr_ec2" {
  name = "${local.prefix}-emr-ec2-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "ec2.amazonaws.com"
      }
    }]
  })

  tags = { Name = "${local.prefix}-emr-ec2-role" }
}

resource "aws_iam_instance_profile" "emr_ec2" {
  name = "${local.prefix}-emr-ec2-profile"
  role = aws_iam_role.emr_ec2.name
}

resource "aws_iam_role_policy" "emr_ec2" {
  name = "${local.prefix}-emr-ec2-policy"
  role = aws_iam_role.emr_ec2.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Hadoop/Spark read raw input and job artifacts.
        Sid      = "S3ReadInputs"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:ListBucket"]
        Resource = concat(local.s3_resources["raw"], local.s3_resources["artifacts"])
      },
      {
        # Hadoop/Spark write curated and graph outputs (DeleteObject for
        # output-committer overwrite). No access to the rag bucket (R16).
        Sid    = "S3WriteOutputs"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:ListBucket",
          "s3:PutObject",
          "s3:DeleteObject",
        ]
        Resource = concat(local.s3_resources["curated"], local.s3_resources["graph"])
      },
      {
        Sid      = "S3WriteLogs"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:ListBucket", "s3:PutObject"]
        Resource = local.s3_resources["logs"]
      },
      {
        Sid    = "DynamoDBAccess"
        Effect = "Allow"
        Action = [
          "dynamodb:BatchGetItem",
          "dynamodb:BatchWriteItem",
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:Query",
          "dynamodb:UpdateItem",
        ]
        Resource = "arn:aws:dynamodb:${local.region}:${local.account_id}:table/${var.project_name}-${var.environment}-*"
      },
      {
        Sid    = "CloudWatchLogs"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents",
        ]
        Resource = "arn:aws:logs:${local.region}:${local.account_id}:log-group:/scholarchain/${var.environment}/*"
      },
      {
        Sid      = "SecretsRead"
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = local.env_secrets_arn
      },
    ]
  })
}

################################################################################
# ECS Task Execution Role
#
# Pulls images and writes container logs (AWS managed policy only). The former
# inline Secrets Manager grant was removed (R8): no container definition in
# modules/ecs has a `secrets` block, so the execution role never injects a
# secret. Add a scoped grant back only together with such a block.
################################################################################

resource "aws_iam_role" "ecs_task_execution" {
  name = "${local.prefix}-ecs-task-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "ecs-tasks.amazonaws.com"
      }
    }]
  })

  tags = { Name = "${local.prefix}-ecs-task-execution-role" }
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution" {
  role       = aws_iam_role.ecs_task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

################################################################################
# ECS Task Role (runtime read-only)
#
# Attached to the five query-path task definitions (query-api,
# graph-retriever, vector-retriever, synthesis-worker, verification-worker).
################################################################################

resource "aws_iam_role" "ecs_task_runtime" {
  name = "${local.prefix}-ecs-task-runtime-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "ecs-tasks.amazonaws.com"
      }
    }]
  })

  tags = { Name = "${local.prefix}-ecs-task-runtime-role" }
}

resource "aws_iam_role_policy" "ecs_task_runtime" {
  name = "${local.prefix}-ecs-task-runtime-policy"
  role = aws_iam_role.ecs_task_runtime.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # R16: read only the buckets the query path uses (curated and graph
        # data, rag text pointers from VectorMetadata.textS3Uri). No raw,
        # logs or artifacts access.
        Sid    = "S3ReadOnly"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:ListBucket",
        ]
        Resource = concat(
          local.s3_resources["curated"],
          local.s3_resources["graph"],
          local.s3_resources["rag"],
        )
      },
      {
        Sid    = "DynamoDBRead"
        Effect = "Allow"
        Action = [
          "dynamodb:BatchGetItem",
          "dynamodb:GetItem",
          "dynamodb:Query",
        ]
        Resource = "arn:aws:dynamodb:${local.region}:${local.account_id}:table/${var.project_name}-${var.environment}-*"
      },
      # R6: no SQS statement. No query-path code enqueues, receives or
      # deletes queue messages (no caller of EmbeddingQueueManager under
      # modules/*/src/main), so the runtime role gets no SQS access at all,
      # and in particular cannot consume the embedding queue or the DLQ.
      {
        Sid      = "SecretsRead"
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = local.env_secrets_arn
      },
      # R7 stage 1 (Phase 12 repair-05): the former BedrockInvoke statement
      # (bedrock:InvokeModel on foundation-model/*) is REMOVED. No Bedrock
      # client exists on the query path today (RuntimeComposer.composeStubs is
      # the only composition), so least privilege is no Bedrock grant on the
      # runtime role. No replacement grant is added anywhere. Stage 2 (a
      # scoped grant for the real caller, query-api, per review N17/N21) is
      # described in docs/agent-runs/phase-12/repair-04.md section 5.3 and
      # needs explicit human approval before it is implemented.
      {
        Sid    = "CloudWatchLogs"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents",
        ]
        Resource = "arn:aws:logs:${local.region}:${local.account_id}:log-group:/scholarchain/${var.environment}/*"
      },
      {
        # Calls made by S3VectorsClient on the query path: QueryVectors, and
        # GetVectors (needed by QueryVectors with a filter or returnMetadata,
        # and called directly in batches of <= 100 keys). GetIndex removed
        # (R15): no code path calls it.
        Sid    = "S3VectorsQuery"
        Effect = "Allow"
        Action = [
          "s3vectors:QueryVectors",
          "s3vectors:GetVectors",
        ]
        Resource = var.s3vectors_index_arns
      },
      {
        # R5: IAM database auth on this cluster only, read-only openCypher.
        # NeptuneClient signs every request with SigV4, service neptune-db,
        # region SCHOLARCHAIN_NEPTUNE_REGION, POST https://<endpoint>:8182/openCypher.
        # No WriteDataViaQuery / DeleteDataViaQuery: the runtime only reads.
        Sid    = "NeptuneRead"
        Effect = "Allow"
        Action = [
          "neptune-db:connect",
          "neptune-db:ReadDataViaQuery",
        ]
        Resource = local.neptune_db_arn
      },
    ]
  })
}

################################################################################
# ECS Task Role (loader - read-write)
#
# Attached only to the embedding-worker task definition.
################################################################################

resource "aws_iam_role" "ecs_task_loader" {
  name = "${local.prefix}-ecs-task-loader-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "ecs-tasks.amazonaws.com"
      }
    }]
  })

  tags = { Name = "${local.prefix}-ecs-task-loader-role" }
}

resource "aws_iam_role_policy" "ecs_task_loader" {
  name = "${local.prefix}-ecs-task-loader-policy"
  role = aws_iam_role.ecs_task_loader.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # EmbeddingBatchWriter.storeTextInS3 writes text to the rag bucket.
        # No DeleteObject: no code path deletes S3 objects (R16).
        Sid    = "S3RagReadWrite"
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:ListBucket",
          "s3:PutObject",
        ]
        Resource = local.s3_resources["rag"]
      },
      {
        Sid      = "S3SourceRead"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:ListBucket"]
        Resource = concat(local.s3_resources["curated"], local.s3_resources["graph"])
      },
      {
        Sid    = "DynamoDBReadWrite"
        Effect = "Allow"
        Action = [
          "dynamodb:BatchGetItem",
          "dynamodb:BatchWriteItem",
          "dynamodb:GetItem",
          "dynamodb:PutItem",
          "dynamodb:Query",
          "dynamodb:UpdateItem",
          "dynamodb:DeleteItem",
        ]
        Resource = "arn:aws:dynamodb:${local.region}:${local.account_id}:table/${var.project_name}-${var.environment}-*"
      },
      {
        # Embedding work queue only (enqueue retries, consume, ack). Not the
        # dead-letter queue: DLQ messages are failure evidence (R6).
        Sid    = "SQSEmbeddingQueue"
        Effect = "Allow"
        Action = [
          "sqs:SendMessage",
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
        ]
        Resource = var.embedding_queue_arn
      },
      {
        Sid      = "SecretsRead"
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = local.env_secrets_arn
      },
      {
        Sid    = "CloudWatchLogs"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents",
        ]
        Resource = "arn:aws:logs:${local.region}:${local.account_id}:log-group:/scholarchain/${var.environment}/*"
      },
      {
        # Embedding writer. Only PutVectors is called
        # (EmbeddingBatchWriter.scala:86 -> S3VectorsStorageClient.putVectors).
        # GetVectors and ListVectors removed (R15). DeleteVectors has been
        # removed from the code; it, GetIndex and ListIndexes are not granted.
        Sid    = "S3VectorsWrite"
        Effect = "Allow"
        Action = [
          "s3vectors:PutVectors",
        ]
        Resource = var.s3vectors_index_arns
      },
      {
        # R3, human-approved exact scope: one action, one model ARN, used
        # verbatim from var.titan_embedding_model_arn. EmbeddingJob carries
        # textS3Uri and no vector, so the worker must embed the text itself.
        # No other Bedrock action or model is granted to this role.
        Sid      = "BedrockTitanEmbed"
        Effect   = "Allow"
        Action   = ["bedrock:InvokeModel"]
        Resource = var.titan_embedding_model_arn
      },
      # R5: no neptune-db grant. NeptuneClient.scala has no write or loader
      # path (generateBulkLoadManifest only builds JSON; nothing calls
      # StartLoaderJob or sends a write query).
    ]
  })
}

################################################################################
# Neptune Loader Role
################################################################################

resource "aws_iam_role" "neptune_loader" {
  name = "${local.prefix}-neptune-loader-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "rds.amazonaws.com"
      }
    }]
  })

  tags = { Name = "${local.prefix}-neptune-loader-role" }
}

resource "aws_iam_role_policy" "neptune_loader" {
  name = "${local.prefix}-neptune-loader-policy"
  role = aws_iam_role.neptune_loader.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "S3GraphRead"
      Effect = "Allow"
      Action = [
        "s3:GetObject",
        "s3:ListBucket",
      ]
      Resource = local.s3_resources["graph"]
    }]
  })
}

################################################################################
# Step Functions Role
################################################################################

resource "aws_iam_role" "step_functions" {
  name = "${local.prefix}-step-functions-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "states.amazonaws.com"
      }
    }]
  })

  tags = { Name = "${local.prefix}-step-functions-role" }
}

resource "aws_iam_role_policy" "step_functions" {
  name = "${local.prefix}-step-functions-policy"
  role = aws_iam_role.step_functions.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [
        {
          # R2: RunTask is authorized on the task-definition resource
          # (arn:...:task-definition/<family>:<revision>, AWS Service
          # Authorization Reference). Any revision of the five query-path
          # families, and only on this environment's cluster.
          Sid    = "ECSRunQueryTasks"
          Effect = "Allow"
          Action = ["ecs:RunTask"]
          Resource = [
            for f in var.query_task_definition_families :
            "arn:aws:ecs:${local.region}:${local.account_id}:task-definition/${f}:*"
          ]
          Condition = {
            ArnEquals = {
              "ecs:cluster" = var.ecs_cluster_arn
            }
          }
        },
        {
          # StopTask / DescribeTasks are authorized on the task resource
          # (arn:...:task/<cluster>/<task-id>).
          Sid      = "ECSManageTasks"
          Effect   = "Allow"
          Action   = ["ecs:StopTask", "ecs:DescribeTasks"]
          Resource = local.ecs_task_arn_pattern
        },
        {
          # Required by the ecs:runTask.sync integration: Step Functions
          # manages this AWS-named rule to receive ECS task state changes.
          Sid    = "ECSSyncEventsRule"
          Effect = "Allow"
          Action = [
            "events:PutTargets",
            "events:PutRule",
            "events:DescribeRule",
          ]
          Resource = "arn:aws:events:${local.region}:${local.account_id}:rule/StepFunctionsGetEventsForECSTaskRule"
        },
        {
          Sid    = "IAMPassRole"
          Effect = "Allow"
          Action = "iam:PassRole"
          Resource = [
            aws_iam_role.ecs_task_execution.arn,
            aws_iam_role.ecs_task_runtime.arn,
          ]
        },
        {
          Sid    = "EventBridgePut"
          Effect = "Allow"
          Action = [
            "events:PutEvents",
          ]
          Resource = "arn:aws:events:${local.region}:${local.account_id}:event-bus/default"
        },
        {
          Sid    = "CloudWatchLogs"
          Effect = "Allow"
          Action = [
            "logs:CreateLogDelivery",
            "logs:GetLogDelivery",
            "logs:UpdateLogDelivery",
            "logs:DeleteLogDelivery",
            "logs:ListLogDeliveries",
            "logs:PutResourcePolicy",
            "logs:DescribeResourcePolicies",
            "logs:DescribeLogGroups",
          ]
          Resource = "*"
        },
      ],
      # R23: SQS only for a dedicated audit queue, never the embedding queue.
      var.audit_queue_arn == null ? [] : [
        {
          Sid      = "SQSSendAudit"
          Effect   = "Allow"
          Action   = ["sqs:SendMessage"]
          Resource = var.audit_queue_arn
        },
      ],
    )
  })
}

################################################################################
# Deploy Role
################################################################################

resource "aws_iam_role" "deploy" {
  name = "${local.prefix}-deploy-role"

  # R17: MFA is always required. Principals are the explicit list when given,
  # otherwise the account root principal (which delegates to IAM policies in
  # this account), still gated by MFA.
  #
  # N12 (known limitation, MFA deliberately kept): aws:MultiFactorAuthPresent
  # is only present in sessions created with MFA by IAM users (GetSessionToken
  # / AssumeRole with SerialNumber+TokenCode). IAM Identity Center (SSO)
  # sessions and role-chained sessions do not carry it, so AssumeRole into
  # this role from those sessions is DENIED. Operators must assume it from an
  # IAM user session with MFA, or a human must approve a different trust
  # model (e.g. trusting a specific Identity Center permission-set role ARN
  # whose sign-in already enforces MFA). Do not remove the MFA condition
  # without that approval.
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        AWS = length(var.deploy_role_trusted_principal_arns) > 0 ? var.deploy_role_trusted_principal_arns : ["arn:aws:iam::${local.account_id}:root"]
      }
      Condition = {
        Bool = {
          "aws:MultiFactorAuthPresent" = "true"
        }
      }
    }]
  })

  tags = { Name = "${local.prefix}-deploy-role" }
}

resource "aws_iam_role_policy" "deploy" {
  name = "${local.prefix}-deploy-policy"
  role = aws_iam_role.deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # R17: service-level actions scoped to this environment's services.
        Sid      = "ECSServiceDeployment"
        Effect   = "Allow"
        Action   = ["ecs:UpdateService", "ecs:DescribeServices"]
        Resource = local.ecs_service_arn_pattern
      },
      {
        Sid      = "ECSDescribeTasks"
        Effect   = "Allow"
        Action   = ["ecs:DescribeTasks"]
        Resource = local.ecs_task_arn_pattern
      },
      {
        # ListTasks is authorized on container-instance; restrict by cluster.
        Sid      = "ECSListTasks"
        Effect   = "Allow"
        Action   = ["ecs:ListTasks"]
        Resource = "*"
        Condition = {
          ArnEquals = {
            "ecs:cluster" = var.ecs_cluster_arn
          }
        }
      },
      {
        Sid      = "ECSRegisterTaskDefinition"
        Effect   = "Allow"
        Action   = ["ecs:RegisterTaskDefinition"]
        Resource = "arn:aws:ecs:${local.region}:${local.account_id}:task-definition/${local.prefix}-*"
      },
      {
        # These two actions support no resource-level scoping (SAR).
        Sid    = "ECSTaskDefinitionRead"
        Effect = "Allow"
        Action = [
          "ecs:DescribeTaskDefinition",
          "ecs:DeregisterTaskDefinition",
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "aws:RequestedRegion" = local.region
          }
        }
      },
      {
        Sid    = "ECRPush"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage",
          "ecr:PutImage",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
        ]
        Resource = "arn:aws:ecr:${local.region}:${local.account_id}:repository/${var.project_name}-${var.environment}-*"
      },
      {
        Sid    = "ECRAuth"
        Effect = "Allow"
        Action = [
          "ecr:GetAuthorizationToken",
        ]
        Resource = "*"
      },
      {
        Sid    = "IAMPassRole"
        Effect = "Allow"
        Action = "iam:PassRole"
        Resource = [
          aws_iam_role.ecs_task_execution.arn,
          aws_iam_role.ecs_task_runtime.arn,
          aws_iam_role.ecs_task_loader.arn,
        ]
      },
      {
        # N12: scoped to THIS environment's EMR cluster by tag. The cluster ARN
        # cannot be referenced directly (module.emr depends on this module's
        # EMR roles, so emr cluster ARN -> iam would be a cycle). The provider
        # default_tags put Project = "ScholarChain" and Environment =
        # <env> on the cluster (providers.tf), and these four actions accept
        # the elasticmapreduce:ResourceTag/<key> condition on the cluster
        # resource, so a dev deploy role cannot add or read steps on prod's
        # cluster (or any untagged cluster).
        Sid    = "EMRSteps"
        Effect = "Allow"
        Action = [
          "elasticmapreduce:AddJobFlowSteps",
          "elasticmapreduce:DescribeStep",
          "elasticmapreduce:ListSteps",
          "elasticmapreduce:DescribeCluster",
        ]
        Resource = "arn:aws:elasticmapreduce:${local.region}:${local.account_id}:cluster/*"
        Condition = {
          StringEquals = {
            "elasticmapreduce:ResourceTag/Environment" = var.environment
            "elasticmapreduce:ResourceTag/Project"     = "ScholarChain"
          }
        }
      },
    ]
  })
}
