################################################################################
# Remote state backend (partial configuration)
#
# Each environment has its own state object. The bucket, key, region and
# use_lockfile (S3 native locking; no DynamoDB lock table) are NOT set here;
# they come from a per-environment backend config file passed at init time:
#
#   terraform init -reconfigure -backend-config=environments/dev.backend.hcl
#   terraform init -reconfigure -backend-config=environments/prod.backend.hcl
#
#   dev  -> key = "scholarchain/dev/terraform.tfstate"
#   prod -> key = "scholarchain/prod/terraform.tfstate"
#
# Why backend-config files rather than workspaces: with workspaces, a missed
# `terraform workspace select` silently plans against whichever workspace is
# current (often "default"), and the key is derived implicitly. With a
# per-environment file the key is explicit, reviewable in Git, and
# scripts/tf-env.sh re-initialises with -reconfigure on every run and refuses
# to continue unless the initialised key equals scholarchain/<env>/... .
# A bare `terraform init` with no -backend-config has no bucket or key, so it
# cannot silently attach to another environment's state.
#
# The state bucket (created by a human from infra/bootstrap, us-east-2) is
# named in the .backend.hcl files or via SCHOLARCHAIN_STATE_BUCKET (tf-env.sh).
################################################################################

terraform {
  backend "s3" {
    encrypt = true
  }
}
