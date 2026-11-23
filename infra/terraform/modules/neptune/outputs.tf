output "cluster_endpoint" {
  description = "Neptune cluster endpoint"
  value       = aws_neptune_cluster.main.endpoint
}

output "cluster_reader_endpoint" {
  description = "Neptune cluster reader endpoint"
  value       = aws_neptune_cluster.main.reader_endpoint
}

output "cluster_id" {
  description = "Neptune cluster identifier"
  value       = aws_neptune_cluster.main.id
}

output "cluster_port" {
  description = "Neptune cluster port"
  value       = aws_neptune_cluster.main.port
}

output "cluster_resource_id" {
  description = "Neptune cluster resource ID (cluster-XXXX). IAM database-auth policies use arn:aws:neptune-db:<region>:<account>:<cluster_resource_id>/*."
  value       = aws_neptune_cluster.main.cluster_resource_id
}

output "audit_log_group_name" {
  description = "CloudWatch log group receiving the Neptune audit log export (N20)"
  value       = aws_cloudwatch_log_group.audit.name
}

output "final_snapshot_identifier" {
  description = "Final snapshot identifier used if this cluster is destroyed (prod only; null in dev). Unique per cluster lifetime (N13)."
  value       = aws_neptune_cluster.main.final_snapshot_identifier
}
