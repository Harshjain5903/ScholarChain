variable "aws_region" {
  description = "Region of the state bucket. Only us-east-2 is usable in this organization (SCP denies us-east-1 and us-west-2)."
  type        = string
  default     = "us-east-2"

  validation {
    condition     = var.aws_region == "us-east-2"
    error_message = "The state bucket must live in us-east-2 (organization SCP)."
  }
}

variable "bucket_suffix" {
  description = "Non-guessable suffix that makes the globally unique state bucket name: scholarchain-tfstate-<suffix>. No default. Lowercase letters and digits, 8-24 characters, e.g. 12 random hex characters. Do not hardcode an account id in the repository."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9]{8,24}$", var.bucket_suffix))
    error_message = "bucket_suffix must be 8-24 lowercase letters or digits."
  }
}

variable "noncurrent_version_expiration_days" {
  description = "Days after which noncurrent (superseded) state object versions are deleted."
  type        = number
  default     = 90

  validation {
    condition     = var.noncurrent_version_expiration_days >= 7
    error_message = "Keep noncurrent state versions for at least 7 days."
  }
}

variable "tag_owner" {
  description = "Optional Owner tag (team or role, not personal data). Omitted when null."
  type        = string
  default     = null
}
