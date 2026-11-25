# Backend config for the dev environment. Used only via
#   terraform init -reconfigure -backend-config=environments/dev.backend.hcl
# (scripts/tf-env.sh does this and verifies the result afterwards).
#
# The state bucket is created once by a human from infra/bootstrap (local
# state, us-east-2; see infra/bootstrap/README.md) and is not managed by this
# configuration. Organization SCP: only us-east-2 is usable, so the state lives
# there too.
#
# bucket: replace REPLACE_WITH_BUCKET_SUFFIX with the suffix used for the
# bootstrap bucket, OR leave the placeholder and export
# SCHOLARCHAIN_STATE_BUCKET=scholarchain-tfstate-<suffix> (tf-env.sh passes it
# as -backend-config=bucket=...). tf-env.sh refuses the placeholder.
#
# Locking: S3 native lockfile (use_lockfile, Terraform >= 1.10). There is no
# DynamoDB lock table and dynamodb_table must not be set.
bucket       = "scholarchain-tfstate-REPLACE_WITH_BUCKET_SUFFIX"
key          = "scholarchain/dev/terraform.tfstate"
region       = "us-east-2"
use_lockfile = true
encrypt      = true
