variable "project_name" {
  description = "Project name for resource naming"
  type        = string
}

variable "environment" {
  description = "Deployment environment"
  type        = string
}

################################################################################
# S3 Vectors
################################################################################

variable "vector_bucket_name" {
  description = "S3 Vectors bucket name. null means \"<project>-<environment>-vectors\"."
  type        = string
  default     = null

  validation {
    condition     = var.vector_bucket_name == null || can(regex("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$", var.vector_bucket_name))
    error_message = "vector_bucket_name must be 3-63 characters of lowercase letters, digits and hyphens, starting and ending with a letter or digit."
  }
}

variable "vector_index_names" {
  description = "S3 Vectors index names. Must match S3VectorsIndexes.all in modules/storage."
  type        = set(string)
  default = [
    "researcher-profile-v1",
    "collaboration-community-v1",
    "paper-abstract-v1",
    "collaboration-path-v1",
    "topic-institution-summary-v1",
  ]

  validation {
    condition     = length(var.vector_index_names) > 0 && alltrue([for n in var.vector_index_names : can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", n))])
    error_message = "Each index name must be 3-63 characters of lowercase letters, digits, hyphens and dots."
  }
}

variable "vector_dimension" {
  description = "Embedding dimension for every index (human decision 2026-11-22: 1024, amazon.titan-embed-text-v2:0). No default: callers must pass it explicitly."
  type        = number

  validation {
    condition     = var.vector_dimension >= 1 && var.vector_dimension <= 4096 && floor(var.vector_dimension) == var.vector_dimension
    error_message = "vector_dimension must be an integer between 1 and 4096."
  }
}

variable "vector_distance_metric" {
  description = "Distance metric for every index (human decision 2026-11-22: cosine). No default: callers must pass it explicitly."
  type        = string

  validation {
    condition     = contains(["cosine", "euclidean"], var.vector_distance_metric)
    error_message = "vector_distance_metric must be \"cosine\" or \"euclidean\"."
  }
}

variable "vector_data_type" {
  description = "Vector data type. S3 Vectors supports float32 only; the application writes Vector[Float]."
  type        = string
  default     = "float32"

  validation {
    condition     = var.vector_data_type == "float32"
    error_message = "vector_data_type must be \"float32\"."
  }
}

variable "vector_non_filterable_metadata_keys" {
  description = "Metadata keys stored but not usable in query filters (long pointer fields from VectorMetadata). All other keys stay filterable."
  type        = list(string)
  default     = ["textS3Uri", "textHash"]

  validation {
    condition     = length(var.vector_non_filterable_metadata_keys) <= 10
    error_message = "S3 Vectors allows at most 10 non-filterable metadata keys per index."
  }
}
