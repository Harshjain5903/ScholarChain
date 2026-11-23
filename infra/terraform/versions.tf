terraform {
  # >= 1.9.0: root variable validations reference other variables
  # (availability_zones vs aws_region, titan grant region check).
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # Exact pin. 6.24.0 introduced aws_s3vectors_vector_bucket and
      # aws_s3vectors_index; 6.26.0 added the index metadata_configuration
      # (non-filterable keys) and encryption_configuration blocks, which are
      # immutable after index creation and therefore required up front.
      version = "= 6.26.0"
    }
  }
}
