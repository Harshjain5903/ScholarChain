output "bucket_arns" {
  description = "Map of bucket purpose to ARN"
  value       = { for k, v in aws_s3_bucket.buckets : k => v.arn }
}

output "bucket_names" {
  description = "Map of bucket purpose to name"
  value       = { for k, v in aws_s3_bucket.buckets : k => v.id }
}

output "logs_bucket_name" {
  description = "Logs bucket name (for EMR and other services)"
  value       = aws_s3_bucket.buckets["logs"].id
}

output "dynamodb_table_arns" {
  description = "Map of table name to ARN"
  value = {
    run_ledger        = aws_dynamodb_table.run_ledger.arn
    entity_link_cache = aws_dynamodb_table.entity_link_cache.arn
    query_session     = aws_dynamodb_table.query_session.arn
    evidence_bundle   = aws_dynamodb_table.evidence_bundle.arn
    artifact_registry = aws_dynamodb_table.artifact_registry.arn
  }
}

output "vector_bucket_name" {
  description = "S3 Vectors bucket name"
  value       = aws_s3vectors_vector_bucket.vectors.vector_bucket_name
}

output "vector_bucket_arn" {
  description = "S3 Vectors bucket ARN"
  value       = aws_s3vectors_vector_bucket.vectors.vector_bucket_arn
}

output "vector_index_arns" {
  description = "Map of S3 Vectors index name to index ARN"
  value       = { for k, v in aws_s3vectors_index.indexes : k => v.index_arn }
}

output "vector_dimension" {
  description = "Embedding dimension shared by every S3 Vectors index"
  value       = var.vector_dimension
}

output "dynamodb_table_names" {
  description = "Map of logical name to DynamoDB table name"
  value = {
    run_ledger        = aws_dynamodb_table.run_ledger.name
    entity_link_cache = aws_dynamodb_table.entity_link_cache.name
    query_session     = aws_dynamodb_table.query_session.name
    evidence_bundle   = aws_dynamodb_table.evidence_bundle.name
    artifact_registry = aws_dynamodb_table.artifact_registry.name
  }
}

output "vector_distance_metric" {
  description = "Distance metric shared by every S3 Vectors index"
  value       = var.vector_distance_metric
}

output "dynamodb_table_prefix" {
  description = "Prefix the application prepends to every table name (DynamoDbClient.scala tableName = \"$tablePrefix-$suffix\"). Exported to ECS as SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX."
  value       = local.prefix
}
