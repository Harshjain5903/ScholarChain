# Backend config for the prod environment. Used only via
#   terraform init -reconfigure -backend-config=environments/prod.backend.hcl
# (scripts/tf-env.sh does this and verifies the key afterwards).
#
# bucket, region and dynamodb_table identify the pre-existing state bucket
# and lock table (not managed by this configuration). region is the state
# bucket's region, not the deployment region (var.aws_region).
#
# Deliberately NOT changed by the 2026-11-23 us-east-2 decision: this value
# must equal the region the existing state bucket actually lives in, and
# editing it does not move the bucket. It stays us-east-1 until a human
# confirms the bucket/lock table region (or creates them in us-east-2 and
# updates both backend files together).
bucket         = "scholarchain-terraform-state"
key            = "scholarchain/prod/terraform.tfstate"
region         = "us-east-1"
dynamodb_table = "scholarchain-terraform-locks"
encrypt        = true
