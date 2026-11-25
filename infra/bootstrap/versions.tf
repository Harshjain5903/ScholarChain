terraform {
  # use_lockfile (S3 native locking) in the environments' backend config
  # needs Terraform >= 1.10.
  required_version = ">= 1.10.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.26.0" # same exact pin as infra/terraform
    }
  }
}
