################################################################################
# Step Functions State Machine - Query Workflow
################################################################################

locals {
  prefix = "${var.project_name}-${var.environment}"

  # R23: audit messages go only to a dedicated audit queue. With no audit
  # queue configured the Audit states are Pass states (no message sent),
  # rather than misrouting audit records onto the embedding job queue.
  # The two branches have different shapes, so each is JSON-encoded to a
  # string (a single type) and decoded where it is used.
  audit_enabled = var.audit_queue_url != null

  audit_success_state = local.audit_enabled ? jsonencode({
    Type     = "Task"
    Resource = "arn:aws:states:::sqs:sendMessage"
    Parameters = {
      QueueUrl = var.audit_queue_url
      MessageBody = {
        "type"      = "audit"
        "queryId.$" = "$.queryId"
        "status"    = "SUCCESS"
      }
    }
    Next = "NotifyComplete"
    }) : jsonencode({
    Type    = "Pass"
    Comment = "No audit queue configured (audit_queue_url = null); nothing is sent."
    Next    = "NotifyComplete"
  })

  audit_failure_state = local.audit_enabled ? jsonencode({
    Type     = "Task"
    Resource = "arn:aws:states:::sqs:sendMessage"
    Parameters = {
      QueueUrl = var.audit_queue_url
      MessageBody = {
        "type"      = "audit"
        "queryId.$" = "$.queryId"
        "status"    = "FAILED"
        "error.$"   = "$.error"
      }
    }
    Next = "WorkflowFailed"
    }) : jsonencode({
    Type    = "Pass"
    Comment = "No audit queue configured (audit_queue_url = null); nothing is sent."
    Next    = "WorkflowFailed"
  })
}

resource "aws_sfn_state_machine" "query_workflow" {
  name     = "${local.prefix}-query-workflow"
  role_arn = var.step_functions_role_arn

  definition = jsonencode({
    Comment = "ScholarChain Graph-RAG query workflow"
    StartAt = "Plan"
    States = {
      Plan = {
        Type     = "Task"
        Resource = "arn:aws:states:::ecs:runTask.sync"
        Parameters = {
          LaunchType     = "FARGATE"
          Cluster        = var.ecs_cluster_arn
          TaskDefinition = "${local.prefix}-query-api"
          Overrides = {
            ContainerOverrides = [{
              Name    = "query-api"
              Command = ["plan"]
              Environment = [
                { Name = "QUERY_ID", "Value.$" = "$.queryId" },
              ]
            }]
          }
          NetworkConfiguration = {
            AwsvpcConfiguration = {
              Subnets        = var.app_subnet_ids
              SecurityGroups = [var.app_security_group_id]
            }
          }
        }
        ResultPath = "$.planResult"
        Next       = "Resolve"
        Retry = [{
          ErrorEquals     = ["States.TaskFailed"]
          IntervalSeconds = 5
          MaxAttempts     = 2
          BackoffRate     = 2.0
        }]
        Catch = [{
          ErrorEquals = ["States.ALL"]
          Next        = "AuditFailure"
          ResultPath  = "$.error"
        }]
      }

      Resolve = {
        Type     = "Task"
        Resource = "arn:aws:states:::ecs:runTask.sync"
        Parameters = {
          LaunchType     = "FARGATE"
          Cluster        = var.ecs_cluster_arn
          TaskDefinition = "${local.prefix}-query-api"
          Overrides = {
            ContainerOverrides = [{
              Name    = "query-api"
              Command = ["resolve"]
              Environment = [
                { Name = "QUERY_ID", "Value.$" = "$.queryId" },
              ]
            }]
          }
          NetworkConfiguration = {
            AwsvpcConfiguration = {
              Subnets        = var.app_subnet_ids
              SecurityGroups = [var.app_security_group_id]
            }
          }
        }
        ResultPath = "$.resolveResult"
        Next       = "ParallelRetrieval"
        Retry = [{
          ErrorEquals     = ["States.TaskFailed"]
          IntervalSeconds = 5
          MaxAttempts     = 2
          BackoffRate     = 2.0
        }]
        Catch = [{
          ErrorEquals = ["States.ALL"]
          Next        = "AuditFailure"
          ResultPath  = "$.error"
        }]
      }

      ParallelRetrieval = {
        Type = "Parallel"
        Branches = [
          {
            StartAt = "CollaborationMetrics"
            States = {
              CollaborationMetrics = {
                Type     = "Task"
                Resource = "arn:aws:states:::ecs:runTask.sync"
                Parameters = {
                  LaunchType     = "FARGATE"
                  Cluster        = var.ecs_cluster_arn
                  TaskDefinition = "${local.prefix}-graph-retriever"
                  Overrides = {
                    ContainerOverrides = [{
                      Name    = "graph-retriever"
                      Command = ["metrics"]
                      Environment = [
                        { Name = "QUERY_ID", "Value.$" = "$.queryId" },
                      ]
                    }]
                  }
                  NetworkConfiguration = {
                    AwsvpcConfiguration = {
                      Subnets        = var.app_subnet_ids
                      SecurityGroups = [var.app_security_group_id]
                    }
                  }
                }
                End = true
              }
            }
          },
          {
            StartAt = "PathFinder"
            States = {
              PathFinder = {
                Type     = "Task"
                Resource = "arn:aws:states:::ecs:runTask.sync"
                Parameters = {
                  LaunchType     = "FARGATE"
                  Cluster        = var.ecs_cluster_arn
                  TaskDefinition = "${local.prefix}-graph-retriever"
                  Overrides = {
                    ContainerOverrides = [{
                      Name    = "graph-retriever"
                      Command = ["paths"]
                      Environment = [
                        { Name = "QUERY_ID", "Value.$" = "$.queryId" },
                      ]
                    }]
                  }
                  NetworkConfiguration = {
                    AwsvpcConfiguration = {
                      Subnets        = var.app_subnet_ids
                      SecurityGroups = [var.app_security_group_id]
                    }
                  }
                }
                End = true
              }
            }
          },
          {
            StartAt = "CommunityAnalysis"
            States = {
              CommunityAnalysis = {
                Type     = "Task"
                Resource = "arn:aws:states:::ecs:runTask.sync"
                Parameters = {
                  LaunchType     = "FARGATE"
                  Cluster        = var.ecs_cluster_arn
                  TaskDefinition = "${local.prefix}-graph-retriever"
                  Overrides = {
                    ContainerOverrides = [{
                      Name    = "graph-retriever"
                      Command = ["communities"]
                      Environment = [
                        { Name = "QUERY_ID", "Value.$" = "$.queryId" },
                      ]
                    }]
                  }
                  NetworkConfiguration = {
                    AwsvpcConfiguration = {
                      Subnets        = var.app_subnet_ids
                      SecurityGroups = [var.app_security_group_id]
                    }
                  }
                }
                End = true
              }
            }
          },
          {
            StartAt = "SemanticContext"
            States = {
              SemanticContext = {
                Type     = "Task"
                Resource = "arn:aws:states:::ecs:runTask.sync"
                Parameters = {
                  LaunchType     = "FARGATE"
                  Cluster        = var.ecs_cluster_arn
                  TaskDefinition = "${local.prefix}-vector-retriever"
                  Overrides = {
                    ContainerOverrides = [{
                      Name    = "vector-retriever"
                      Command = ["semantic"]
                      Environment = [
                        { Name = "QUERY_ID", "Value.$" = "$.queryId" },
                      ]
                    }]
                  }
                  NetworkConfiguration = {
                    AwsvpcConfiguration = {
                      Subnets        = var.app_subnet_ids
                      SecurityGroups = [var.app_security_group_id]
                    }
                  }
                }
                End = true
              }
            }
          },
        ]
        ResultPath = "$.parallelResults"
        Next       = "BuildEvidence"
        Catch = [{
          ErrorEquals = ["States.ALL"]
          Next        = "AuditFailure"
          ResultPath  = "$.error"
        }]
      }

      BuildEvidence = {
        Type     = "Task"
        Resource = "arn:aws:states:::ecs:runTask.sync"
        Parameters = {
          LaunchType     = "FARGATE"
          Cluster        = var.ecs_cluster_arn
          TaskDefinition = "${local.prefix}-synthesis-worker"
          Overrides = {
            ContainerOverrides = [{
              Name    = "synthesis-worker"
              Command = ["evidence"]
              Environment = [
                { Name = "QUERY_ID", "Value.$" = "$.queryId" },
              ]
            }]
          }
          NetworkConfiguration = {
            AwsvpcConfiguration = {
              Subnets        = var.app_subnet_ids
              SecurityGroups = [var.app_security_group_id]
            }
          }
        }
        ResultPath = "$.evidenceResult"
        Next       = "Synthesize"
        Retry = [{
          ErrorEquals     = ["States.TaskFailed"]
          IntervalSeconds = 5
          MaxAttempts     = 2
          BackoffRate     = 2.0
        }]
        Catch = [{
          ErrorEquals = ["States.ALL"]
          Next        = "AuditFailure"
          ResultPath  = "$.error"
        }]
      }

      Synthesize = {
        Type     = "Task"
        Resource = "arn:aws:states:::ecs:runTask.sync"
        Parameters = {
          LaunchType     = "FARGATE"
          Cluster        = var.ecs_cluster_arn
          TaskDefinition = "${local.prefix}-synthesis-worker"
          Overrides = {
            ContainerOverrides = [{
              Name    = "synthesis-worker"
              Command = ["synthesize"]
              Environment = [
                { Name = "QUERY_ID", "Value.$" = "$.queryId" },
              ]
            }]
          }
          NetworkConfiguration = {
            AwsvpcConfiguration = {
              Subnets        = var.app_subnet_ids
              SecurityGroups = [var.app_security_group_id]
            }
          }
        }
        ResultPath = "$.synthesisResult"
        Next       = "Verify"
        Retry = [{
          ErrorEquals     = ["States.TaskFailed"]
          IntervalSeconds = 5
          MaxAttempts     = 2
          BackoffRate     = 2.0
        }]
        Catch = [{
          ErrorEquals = ["States.ALL"]
          Next        = "AuditFailure"
          ResultPath  = "$.error"
        }]
      }

      Verify = {
        Type     = "Task"
        Resource = "arn:aws:states:::ecs:runTask.sync"
        Parameters = {
          LaunchType     = "FARGATE"
          Cluster        = var.ecs_cluster_arn
          TaskDefinition = "${local.prefix}-verification-worker"
          Overrides = {
            ContainerOverrides = [{
              Name    = "verification-worker"
              Command = ["verify"]
              Environment = [
                { Name = "QUERY_ID", "Value.$" = "$.queryId" },
              ]
            }]
          }
          NetworkConfiguration = {
            AwsvpcConfiguration = {
              Subnets        = var.app_subnet_ids
              SecurityGroups = [var.app_security_group_id]
            }
          }
        }
        ResultPath = "$.verifyResult"
        Next       = "Audit"
        Retry = [{
          ErrorEquals     = ["States.TaskFailed"]
          IntervalSeconds = 5
          MaxAttempts     = 2
          BackoffRate     = 2.0
        }]
        Catch = [{
          ErrorEquals = ["States.ALL"]
          Next        = "AuditFailure"
          ResultPath  = "$.error"
        }]
      }

      Audit = jsondecode(local.audit_success_state)

      AuditFailure = jsondecode(local.audit_failure_state)

      NotifyComplete = {
        Type     = "Task"
        Resource = "arn:aws:states:::events:putEvents"
        Parameters = {
          Entries = [{
            Source     = "scholarchain.workflow"
            DetailType = "QueryWorkflowComplete"
            Detail = {
              "queryId.$" = "$.queryId"
              "status"    = "SUCCESS"
            }
          }]
        }
        End = true
      }

      WorkflowFailed = {
        Type  = "Fail"
        Error = "WorkflowFailed"
        Cause = "Query workflow encountered an unrecoverable error"
      }
    }
  })

  logging_configuration {
    log_destination        = "${var.workflow_log_group_arn}:*"
    include_execution_data = true
    level                  = "ERROR"
  }

  tags = {
    Name = "${local.prefix}-query-workflow"
  }
}

################################################################################
# SQS Queues
################################################################################

resource "aws_sqs_queue" "dead_letter" {
  name                       = "${local.prefix}-dead-letter-queue"
  message_retention_seconds  = 1209600 # 14 days
  visibility_timeout_seconds = 300

  sqs_managed_sse_enabled = true

  tags = {
    Name = "${local.prefix}-dead-letter-queue"
  }
}

resource "aws_sqs_queue" "embedding_work" {
  name                       = "${local.prefix}-embedding-work-queue"
  visibility_timeout_seconds = 300
  message_retention_seconds  = 86400 # 1 day
  receive_wait_time_seconds  = 20

  sqs_managed_sse_enabled = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dead_letter.arn
    maxReceiveCount     = 3
  })

  tags = {
    Name = "${local.prefix}-embedding-work-queue"
  }
}

################################################################################
# EventBridge Rule - Pipeline Completion
################################################################################

resource "aws_cloudwatch_event_rule" "pipeline_complete" {
  name        = "${local.prefix}-pipeline-complete"
  description = "Fires when the query workflow completes"

  event_pattern = jsonencode({
    source      = ["scholarchain.workflow"]
    detail-type = ["QueryWorkflowComplete"]
  })

  tags = {
    Name = "${local.prefix}-pipeline-complete"
  }
}

resource "aws_cloudwatch_event_target" "pipeline_complete_log" {
  rule      = aws_cloudwatch_event_rule.pipeline_complete.name
  target_id = "log-pipeline-complete"
  arn       = var.workflow_log_group_arn
}
