output "workflow_log_group_arn" {
  description = "Workflow log group ARN"
  value       = aws_cloudwatch_log_group.workflow.arn
}

output "workflow_log_group_name" {
  description = "Workflow log group name"
  value       = aws_cloudwatch_log_group.workflow.name
}

output "dashboard_name" {
  description = "CloudWatch dashboard name"
  value       = aws_cloudwatch_dashboard.main.dashboard_name
}

output "alarm_arns" {
  description = "Map of alarm names to ARNs"
  value = merge(
    {
      api_p99_latency = aws_cloudwatch_metric_alarm.api_p99_latency.arn
      sqs_dlq         = aws_cloudwatch_metric_alarm.sqs_dlq_messages.arn
      neptune_cpu     = aws_cloudwatch_metric_alarm.neptune_cpu.arn
    },
    { for k, v in aws_cloudwatch_metric_alarm.ecs_unhealthy_tasks : "ecs_${k}" => v.arn }
  )
}
