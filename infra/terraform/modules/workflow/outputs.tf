output "state_machine_arn" {
  description = "Step Functions state machine ARN"
  value       = aws_sfn_state_machine.query_workflow.arn
}

output "state_machine_name" {
  description = "Step Functions state machine name"
  value       = aws_sfn_state_machine.query_workflow.name
}

output "embedding_queue_url" {
  description = "Embedding work queue URL"
  value       = aws_sqs_queue.embedding_work.url
}

output "embedding_queue_arn" {
  description = "Embedding work queue ARN"
  value       = aws_sqs_queue.embedding_work.arn
}

output "dead_letter_queue_url" {
  description = "Dead letter queue URL"
  value       = aws_sqs_queue.dead_letter.url
}

output "dead_letter_queue_arn" {
  description = "Dead letter queue ARN"
  value       = aws_sqs_queue.dead_letter.arn
}
