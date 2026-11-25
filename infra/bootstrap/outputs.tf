output "state_bucket_name" {
  description = "Name of the state bucket; put it in infra/terraform/environments/<env>.backend.hcl (or export SCHOLARCHAIN_STATE_BUCKET)."
  value       = aws_s3_bucket.state.id
}

output "state_bucket_region" {
  description = "Region of the state bucket"
  value       = var.aws_region
}

output "account_id" {
  description = "Account the bucket was created in, for the human to compare with the intended account before the first use."
  value       = data.aws_caller_identity.current.account_id
}
