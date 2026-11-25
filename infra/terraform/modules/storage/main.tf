################################################################################
# S3 Buckets
################################################################################

locals {
  prefix = "${var.project_name}-${var.environment}"

  bucket_configs = {
    raw     = { versioning = true }
    curated = { versioning = false }
    graph   = { versioning = true }
    rag     = { versioning = true }
    # N68: versioned so the pipeline can record and pin the S3 VersionId of
    # every job jar it runs (noncurrent versions expire like the other buckets).
    artifacts = { versioning = true }
    logs      = { versioning = false }
  }
}

resource "aws_s3_bucket" "buckets" {
  for_each = local.bucket_configs
  bucket   = "${local.prefix}-${each.key}"

  tags = {
    Name    = "${local.prefix}-${each.key}"
    Purpose = each.key
  }
}

resource "aws_s3_bucket_versioning" "buckets" {
  for_each = local.bucket_configs
  bucket   = aws_s3_bucket.buckets[each.key].id

  versioning_configuration {
    status = each.value.versioning ? "Enabled" : "Suspended"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "buckets" {
  for_each = local.bucket_configs
  bucket   = aws_s3_bucket.buckets[each.key].id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "buckets" {
  for_each = local.bucket_configs
  bucket   = aws_s3_bucket.buckets[each.key].id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "logs" {
  bucket = aws_s3_bucket.buckets["logs"].id

  rule {
    id     = "expire-old-logs"
    status = "Enabled"

    expiration {
      days = var.environment == "prod" ? 365 : 90
    }
  }
}

# N72: the curated run-*/ expiry (var.intermediate_expiration_days, default 60)
# also covers the Hadoop/ER manifests and summaries kept under each run.
# Cost guardrail: intermediate pipeline output and old object versions do not
# accumulate forever. Curated holds only intermediate Hadoop/ER output under
# run-<id>/ (final graph output goes to the graph bucket), so that prefix
# expires; versioned buckets drop noncurrent versions; every bucket aborts
# stale multipart uploads (failed s3a/Spark writes leave invisible parts that
# are still billed).
resource "aws_s3_bucket_lifecycle_configuration" "data" {
  for_each = toset(["raw", "curated", "graph", "rag", "artifacts"])
  bucket   = aws_s3_bucket.buckets[each.key].id

  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  dynamic "rule" {
    for_each = local.bucket_configs[each.key].versioning ? [1] : []

    content {
      id     = "expire-noncurrent-versions"
      status = "Enabled"

      filter {}

      noncurrent_version_expiration {
        noncurrent_days = var.intermediate_expiration_days
      }
    }
  }

  dynamic "rule" {
    for_each = each.key == "curated" ? [1] : []

    content {
      id     = "expire-intermediate-run-output"
      status = "Enabled"

      filter {
        prefix = "run-"
      }

      expiration {
        days = var.intermediate_expiration_days
      }
    }
  }
}

################################################################################
# DynamoDB Tables
#
# Names must equal what the application builds:
#   DynamoDbClient.scala:77  tableName(suffix) = s"$tablePrefix-$suffix"
#   suffixes (lines 88-214): run-ledger, entity-link-cache, query-session,
#   evidence-bundle, artifact-registry
# with tablePrefix = SCHOLARCHAIN_DYNAMODB_TABLE_PREFIX = "<project>-<env>"
# (output dynamodb_table_prefix). Table names are immutable in DynamoDB:
# changing one forces replacement (delete + create) and loses its items.
################################################################################

resource "aws_dynamodb_table" "run_ledger" {
  name         = "${local.prefix}-run-ledger"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "runId"

  attribute {
    name = "runId"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }

  tags = { Name = "${local.prefix}-run-ledger" }
}

resource "aws_dynamodb_table" "entity_link_cache" {
  name         = "${local.prefix}-entity-link-cache"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "sourceId"
  range_key    = "targetId"

  attribute {
    name = "sourceId"
    type = "S"
  }

  attribute {
    name = "targetId"
    type = "S"
  }

  # N5: DynamoDbClient.putEntityLinkCache writes the top-level attribute
  # "ttl" as a Number (AttributeValue.fromN(entry.ttl.toString), from
  # EntityLinkCacheEntry.ttl: Long). DynamoDB TTL requires epoch SECONDS;
  # the only fixture (StorageSpec, 1700000000L) is epoch seconds. A value in
  # milliseconds would merely never expire (fail-safe, no early deletion).
  # TTL deletion is best-effort (typically within days of expiry).
  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }

  tags = { Name = "${local.prefix}-entity-link-cache" }
}

resource "aws_dynamodb_table" "query_session" {
  name         = "${local.prefix}-query-session"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "queryId"

  attribute {
    name = "queryId"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }

  tags = { Name = "${local.prefix}-query-session" }
}

# Partition key must equal the application's: DynamoDbClient.scala
# putEvidenceBundle writes {bundleId, jsonData} with
# attribute_not_exists(bundleId); getEvidenceBundle reads by key bundleId.
# (N1: was evidenceId, which would reject every write. Key schema is
# immutable after creation; nothing has been applied, so no migration.)
resource "aws_dynamodb_table" "evidence_bundle" {
  name         = "${local.prefix}-evidence-bundle"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "bundleId"

  attribute {
    name = "bundleId"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }

  tags = { Name = "${local.prefix}-evidence-bundle" }
}

resource "aws_dynamodb_table" "artifact_registry" {
  name         = "${local.prefix}-artifact-registry"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "artifactId"

  attribute {
    name = "artifactId"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }

  tags = { Name = "${local.prefix}-artifact-registry" }
}

################################################################################
# S3 Vectors
################################################################################

locals {
  vector_bucket_name = coalesce(var.vector_bucket_name, "${local.prefix}-vectors")
}

resource "aws_s3vectors_vector_bucket" "vectors" {
  vector_bucket_name = local.vector_bucket_name

  # SSE-S3. No customer-managed KMS key exists in this configuration.
  encryption_configuration = [{
    sse_type    = "AES256"
    kms_key_arn = null
  }]

  tags = {
    Name    = local.vector_bucket_name
    Purpose = "vectors"
  }

  # R19: the vector bucket holds every embedding; deleting it loses them
  # irrecoverably. lifecycle arguments cannot reference variables, so this is
  # unconditional for every environment. scripts/cleanup.sh deliberately
  # leaves this bucket and its indexes out of its destroy plan. To remove
  # them, a human must make a reviewed commit that deletes these
  # prevent_destroy lines (or sets them to false), then plan and apply
  # through the normal human gate. See the procedure printed by cleanup.sh.
  lifecycle {
    prevent_destroy = true
  }
}

# One index per name in var.vector_index_names. Index names, data type and the
# metadata schema mirror modules/storage S3VectorsIndexes and VectorMetadata.
# Dimension, distance metric and non-filterable keys cannot be changed after
# creation; changing them forces index replacement.
resource "aws_s3vectors_index" "indexes" {
  for_each = var.vector_index_names

  vector_bucket_name = aws_s3vectors_vector_bucket.vectors.vector_bucket_name
  index_name         = each.key
  data_type          = var.vector_data_type
  dimension          = var.vector_dimension
  distance_metric    = var.vector_distance_metric

  metadata_configuration {
    non_filterable_metadata_keys = var.vector_non_filterable_metadata_keys
  }

  tags = {
    Name  = "${local.vector_bucket_name}-${each.key}"
    Index = each.key
  }

  # R19: dimension, distance metric, data type and metadata configuration are
  # immutable; any change forces replacement and loses every vector in the
  # index. prevent_destroy makes such a plan fail instead. Same removal
  # procedure as the vector bucket above.
  lifecycle {
    prevent_destroy = true
  }
}
