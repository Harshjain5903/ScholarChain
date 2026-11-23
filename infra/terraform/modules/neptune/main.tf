################################################################################
# Neptune Serverless Cluster
################################################################################

locals {
  prefix             = "${var.project_name}-${var.environment}"
  cluster_identifier = "${local.prefix}-neptune"
}

################################################################################
# N20: audit log export
#
# neptune_enable_audit_log = 1 (parameter group) only writes audit records
# on the instances; enable_cloudwatch_logs_exports = ["audit"] publishes them
# to CloudWatch Logs, into the fixed group /aws/neptune/<cluster-id>/audit.
# The group is created here first (depends_on on the cluster) so Terraform
# owns its retention and destroys it with the environment, and
# scripts/archive-evidence.sh exports it before cleanup.
################################################################################

resource "aws_cloudwatch_log_group" "audit" {
  name              = "/aws/neptune/${local.cluster_identifier}/audit"
  retention_in_days = var.log_retention_days

  tags = {
    Name = "${local.prefix}-neptune-audit-logs"
  }
}

################################################################################
# N13: unique final snapshot identifier per cluster lifetime
#
# Why a stored timestamp and not a variable that cleanup.sh supplies: on
# destroy the AWS provider deletes the cluster using final_snapshot_identifier
# from the PRIOR STATE, not from the destroy-time configuration, so a value
# passed only to the destroy plan would be ignored (and passing a fresh value
# on every deploy would make every plan show a perpetual in-place diff).
# terraform_data (built in; no extra provider or lock-file entry) records
# timestamp() once, when it is created alongside the cluster, and
# ignore_changes keeps it fixed afterwards, so plans stay clean. Every
# destroy + redeploy cycle creates a new terraform_data and therefore a new
# identifier: <prefix>-neptune-final-<YYYYMMDDhhmmss>.
# Residual case: if the cluster alone is REPLACED (forced-new change) while
# this resource is kept, the replacement's final snapshot and a later
# destroy's would share a name; cleanup.sh checks for an existing snapshot
# with the identifier before planning the destroy and refuses with a message.
################################################################################

resource "terraform_data" "final_snapshot_stamp" {
  input = timestamp()

  lifecycle {
    ignore_changes = [input]
  }
}

resource "aws_neptune_subnet_group" "main" {
  name       = "${local.prefix}-neptune-subnet-group"
  subnet_ids = var.subnet_ids

  tags = {
    Name = "${local.prefix}-neptune-subnet-group"
  }
}

resource "aws_neptune_cluster_parameter_group" "main" {
  family = "neptune1.3"
  name   = "${local.prefix}-neptune-params"

  parameter {
    name  = "neptune_enable_audit_log"
    value = "1"
  }

  tags = {
    Name = "${local.prefix}-neptune-params"
  }
}

resource "aws_neptune_cluster" "main" {
  cluster_identifier                   = local.cluster_identifier
  engine                               = "neptune"
  engine_version                       = "1.3.2.1"
  neptune_subnet_group_name            = aws_neptune_subnet_group.main.name
  neptune_cluster_parameter_group_name = aws_neptune_cluster_parameter_group.main.name
  vpc_security_group_ids               = [var.security_group_id]
  storage_encrypted                    = true
  iam_database_authentication_enabled  = true
  deletion_protection                  = var.deletion_protection
  skip_final_snapshot                  = var.environment != "prod"
  final_snapshot_identifier            = var.environment == "prod" ? "${local.prefix}-neptune-final-${formatdate("YYYYMMDDhhmmss", terraform_data.final_snapshot_stamp.output)}" : null
  backup_retention_period              = var.environment == "prod" ? 7 : 1
  preferred_backup_window              = "03:00-04:00"

  # N20 / Trivy AWS-0075: publish the audit log (enabled in the parameter
  # group) to /aws/neptune/<cluster-id>/audit.
  enable_cloudwatch_logs_exports = ["audit"]

  serverless_v2_scaling_configuration {
    min_capacity = var.min_capacity
    max_capacity = var.max_capacity
  }

  iam_roles = [var.neptune_loader_role_arn]

  tags = {
    Name = "${local.prefix}-neptune"
  }

  depends_on = [aws_cloudwatch_log_group.audit]
}

resource "aws_neptune_cluster_instance" "main" {
  count              = 1
  cluster_identifier = aws_neptune_cluster.main.id
  instance_class     = "db.serverless"
  engine             = "neptune"
  identifier         = "${local.prefix}-neptune-instance-${count.index}"

  tags = {
    Name = "${local.prefix}-neptune-instance-${count.index}"
  }
}
