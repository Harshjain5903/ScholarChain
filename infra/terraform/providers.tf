################################################################################
# AWS Provider (single root configuration)
#
# This is the only provider "aws" block in the configuration. Child modules
# declare no provider blocks and inherit this default configuration, so
# region and default_tags apply to every resource in every module.
################################################################################

locals {
  # Optional tags are emitted only when their variable is set; no owner,
  # course or expiration value is invented here.
  optional_tags = {
    for k, v in {
      Owner      = var.tag_owner
      Course     = var.tag_course
      Expiration = var.tag_expiration_date
    } : k => v if v != null
  }

  # Mandatory tags are merged LAST so extra_tags (or an optional tag) can
  # never override Project, Environment or ManagedBy. var.extra_tags also
  # rejects those keys in its validation, so a collision fails plan loudly
  # instead of being silently dropped.
  default_tags = merge(
    var.extra_tags,
    local.optional_tags,
    {
      Project     = "ScholarChain"
      Environment = var.environment
      ManagedBy   = "terraform"
    },
  )
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = local.default_tags
  }
}
