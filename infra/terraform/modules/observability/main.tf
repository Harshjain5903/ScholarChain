################################################################################
# Locals
################################################################################

locals {
  prefix = "${var.project_name}-${var.environment}"
}

################################################################################
# CloudWatch Log Groups
################################################################################

resource "aws_cloudwatch_log_group" "workflow" {
  name              = "/scholarchain/${var.environment}/workflow"
  retention_in_days = var.log_retention_days

  tags = { Name = "${local.prefix}-workflow-logs" }
}

resource "aws_cloudwatch_log_group" "emr" {
  name              = "/scholarchain/${var.environment}/emr"
  retention_in_days = var.log_retention_days

  tags = { Name = "${local.prefix}-emr-logs" }
}

resource "aws_cloudwatch_log_group" "neptune" {
  name              = "/scholarchain/${var.environment}/neptune"
  retention_in_days = var.log_retention_days

  tags = { Name = "${local.prefix}-neptune-logs" }
}

locals {
  # The EMR widget exists only when an EMR cluster does (batch_compute = "emr").
  emr_widgets = var.emr_cluster_id == null ? [] : [
    {
      type   = "metric"
      x      = 0
      y      = 0
      width  = 12
      height = 6
      properties = {
        title  = "EMR Cluster - YARN Memory"
        region = var.aws_region
        metrics = [
          ["AWS/ElasticMapReduce", "YARNMemoryAvailablePercentage", "JobFlowId", var.emr_cluster_id],
          ["AWS/ElasticMapReduce", "ContainerAllocated", "JobFlowId", var.emr_cluster_id],
        ]
        period = 300
        stat   = "Average"
      }
    }
  ]
}

################################################################################
# CloudWatch Dashboard
################################################################################

resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = "${local.prefix}-dashboard"

  dashboard_body = jsonencode({
    widgets = concat(local.emr_widgets, [
      {
        type   = "metric"
        x      = 12
        y      = 0
        width  = 12
        height = 6
        properties = {
          title  = "ECS Service - CPU Utilization"
          region = var.aws_region
          metrics = [
            for svc in var.ecs_service_names :
            ["AWS/ECS", "CPUUtilization", "ClusterName", var.ecs_cluster_name, "ServiceName", svc]
          ]
          period = 60
          stat   = "Average"
        }
      },
      {
        type   = "metric"
        x      = 0
        y      = 6
        width  = 12
        height = 6
        properties = {
          title  = "ECS Service - Memory Utilization"
          region = var.aws_region
          metrics = [
            for svc in var.ecs_service_names :
            ["AWS/ECS", "MemoryUtilization", "ClusterName", var.ecs_cluster_name, "ServiceName", svc]
          ]
          period = 60
          stat   = "Average"
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = 6
        width  = 12
        height = 6
        properties = {
          title  = "Neptune - Query Latency & CPU"
          region = var.aws_region
          metrics = [
            ["AWS/Neptune", "GremlinRequestsPerSec", "DBClusterIdentifier", var.neptune_cluster_id],
            ["AWS/Neptune", "GremlinRequestLatency", "DBClusterIdentifier", var.neptune_cluster_id],
            ["AWS/Neptune", "CPUUtilization", "DBClusterIdentifier", var.neptune_cluster_id],
          ]
          period = 60
          stat   = "Average"
        }
      },
      {
        type   = "metric"
        x      = 0
        y      = 12
        width  = 12
        height = 6
        properties = {
          title  = "DynamoDB - Consumed Capacity"
          region = var.aws_region
          metrics = [
            for table in var.dynamodb_table_names :
            ["AWS/DynamoDB", "ConsumedReadCapacityUnits", "TableName", table]
          ]
          period = 300
          stat   = "Sum"
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = 12
        width  = 12
        height = 6
        properties = {
          title  = "SQS - Queue Depth"
          region = var.aws_region
          metrics = [
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", var.embedding_queue_name],
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", var.dead_letter_queue_name],
          ]
          period = 60
          stat   = "Average"
        }
      },
      {
        type   = "metric"
        x      = 0
        y      = 18
        width  = 24
        height = 6
        properties = {
          title  = "ALB - Request Latency (p50/p95/p99)"
          region = var.aws_region
          metrics = [
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, { stat = "p50" }],
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, { stat = "p95" }],
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, { stat = "p99" }],
          ]
          period = 60
        }
      },
    ])
  })
}

################################################################################
# CloudWatch Alarms
################################################################################

resource "aws_cloudwatch_metric_alarm" "api_p99_latency" {
  alarm_name          = "${local.prefix}-api-p99-latency"
  alarm_description   = "API p99 latency exceeds ${var.alarm_api_p99_threshold_ms}ms"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "TargetResponseTime"
  namespace           = "AWS/ApplicationELB"
  period              = 60
  extended_statistic  = "p99"
  threshold           = var.alarm_api_p99_threshold_ms / 1000.0
  treat_missing_data  = "notBreaching"

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
  }

  tags = { Name = "${local.prefix}-api-p99-latency" }
}

resource "aws_cloudwatch_metric_alarm" "sqs_dlq_messages" {
  alarm_name          = "${local.prefix}-sqs-dlq-messages"
  alarm_description   = "Messages detected in dead-letter queue"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Sum"
  threshold           = 0
  treat_missing_data  = "notBreaching"

  dimensions = {
    QueueName = var.dead_letter_queue_name
  }

  tags = { Name = "${local.prefix}-sqs-dlq-messages" }
}

resource "aws_cloudwatch_metric_alarm" "neptune_cpu" {
  alarm_name          = "${local.prefix}-neptune-cpu"
  alarm_description   = "Neptune CPU utilization exceeds ${var.alarm_neptune_cpu_threshold}%"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "CPUUtilization"
  namespace           = "AWS/Neptune"
  period              = 300
  statistic           = "Average"
  threshold           = var.alarm_neptune_cpu_threshold
  treat_missing_data  = "notBreaching"

  dimensions = {
    DBClusterIdentifier = var.neptune_cluster_id
  }

  tags = { Name = "${local.prefix}-neptune-cpu" }
}

# One alarm per ALB target group (map key = short service name, e.g.
# query-api). Previously for_each used the full ECS service names
# (<prefix>-<service>) and indexed this map with them, which does not match
# its short-name keys; services without a target group have no
# HealthyHostCount metric and get no alarm.
resource "aws_cloudwatch_metric_alarm" "ecs_unhealthy_tasks" {
  for_each = var.ecs_target_group_arn_suffixes

  alarm_name          = "${local.prefix}-${each.key}-unhealthy"
  alarm_description   = "ECS service ${each.key} has unhealthy tasks"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 2
  metric_name         = "HealthyHostCount"
  namespace           = "AWS/ApplicationELB"
  period              = 60
  statistic           = "Minimum"
  threshold           = 1
  treat_missing_data  = "breaching"

  dimensions = {
    TargetGroup  = each.value
    LoadBalancer = var.alb_arn_suffix
  }

  tags = {
    Name    = "${local.prefix}-${each.key}-unhealthy"
    Service = each.key
  }
}
