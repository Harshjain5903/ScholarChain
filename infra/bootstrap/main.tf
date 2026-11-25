################################################################################
# ScholarChain Terraform state bucket (bootstrap)
#
# Standalone root with LOCAL state (this directory's terraform.tfstate, which
# is gitignored). It creates ONLY the S3 bucket that holds the remote state of
# infra/terraform. S3 native locking (use_lockfile) replaces the DynamoDB lock
# table, so no table is created.
#
# A human runs this once; see README.md. Nothing here is applied by agents.
################################################################################

data "aws_caller_identity" "current" {}

locals {
  bucket_name = "scholarchain-tfstate-${var.bucket_suffix}"

  tags = merge(
    var.tag_owner == null ? {} : { Owner = var.tag_owner },
    {
      Project     = "ScholarChain"
      Environment = "shared"
      ManagedBy   = "terraform"
      Purpose     = "terraform-state"
    },
  )
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = local.tags
  }
}

resource "aws_s3_bucket" "state" {
  bucket = local.bucket_name

  # State is irreplaceable: a destroy of this root must fail while the bucket
  # holds objects, and the bucket cannot be removed by a stray plan.
  force_destroy = false

  tags = {
    Name = local.bucket_name
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

# SSE-S3 (AES256): no KMS key to create, no per-request KMS charge, and no
# key policy to get wrong when a second operator is added. Switch to aws:kms
# only together with an explicit key policy.
resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

data "aws_iam_policy_document" "state" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.state.arn,
      "${aws_s3_bucket.state.arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = data.aws_iam_policy_document.state.json

  # The public access block must exist first so the policy is never evaluated
  # against a bucket that could still accept a public policy.
  depends_on = [aws_s3_bucket_public_access_block.state]
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-noncurrent-state-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_expiration_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  # Versioning must be enabled before noncurrent-version rules are accepted.
  depends_on = [aws_s3_bucket_versioning.state]
}
