# ScholarChain Terraform state bootstrap

A tiny standalone Terraform root that creates the one S3 bucket holding the
remote state of `infra/terraform` (`dev` and `prod` use different keys in it).
It uses **local state** (`terraform.tfstate` in this directory, gitignored).
There is no DynamoDB lock table: the environments lock through S3 native
locking (`use_lockfile = true`, Terraform >= 1.10).

Region: **us-east-2 only**. The organization SCP denies other regions.

The bucket has versioning, SSE-S3 (AES256), a full public-access block, a
deny-insecure-transport policy, a lifecycle rule that expires noncurrent state
versions (default 90 days), `BucketOwnerEnforced` ownership, and
`prevent_destroy`. Tags: Project, Environment=shared, ManagedBy, Purpose.

Agents never run the create step. A human runs the commands below once.

## One-time commands (human)

```bash
export AWS_PROFILE=scholarchain
export AWS_REGION=us-east-2
aws sts get-caller-identity            # confirm the account is the intended one

cd infra/bootstrap
# Non-guessable suffix: 12 random hex characters. Not a credential, but do not
# derive it from anything public.
SUFFIX="$(openssl rand -hex 6)"

terraform init -lockfile=readonly      # never rewrite .terraform.lock.hcl (N76)
terraform plan -var "bucket_suffix=${SUFFIX}" -out bootstrap.tfplan   # review
terraform apply bootstrap.tfplan                                      # human only
terraform output state_bucket_name     # scholarchain-tfstate-<suffix>
```

(`*.tfplan` and `*.tfstate` are gitignored.)

Then point the environments at the bucket, either by editing the `bucket =`
line in `infra/terraform/environments/dev.backend.hcl` and `prod.backend.hcl`,
or without touching tracked files:

```bash
export SCHOLARCHAIN_STATE_BUCKET="$(terraform output -raw state_bucket_name)"
```

`scripts/tf-env.sh` refuses to run while the bucket is still the
`REPLACE_WITH_BUCKET_SUFFIX` placeholder, passes the override to
`terraform init -backend-config=bucket=...`, and verifies the initialised
backend afterwards (bucket, key, region us-east-2, `use_lockfile = true`, no
lock table).

## Provider lock file (N76)

`.terraform.lock.hcl` pins `hashicorp/aws` 6.26.0. Always initialise with
`terraform init -lockfile=readonly`: Terraform then refuses to run if the
downloaded provider does not match a recorded hash, instead of silently adding
one. The file holds one `h1:` hash (computed on the machine that created it,
darwin_arm64) and the `zh:` hashes of the registry's release zips for every
platform. Terraform accepts a package whose zip matches a `zh:` hash, so
`-lockfile=readonly` also works on linux_amd64 (CI, a Linux workstation).
A `linux_amd64` `h1:` hash could NOT be added offline: it is the hash of the
unpacked provider and needs the linux_amd64 zip downloaded from the registry
(`terraform providers lock -platform=linux_amd64 -platform=darwin_arm64`, a
human step, network needed but no AWS access). Until then a Linux `init` works
through the `zh:` hashes only.

The state-bucket check in `scripts/tf-env.sh` additionally runs
`aws s3api head-bucket --expected-bucket-owner <your account>` before any
`terraform init` of an environment (N76), so a same-named bucket in someone
else's account is refused.

## Why dev does not use EMR

The course AWS organization's service control policy (SCP) explicitly denies
`elasticmapreduce:*` and `emr-serverless:*`, so no EMR cluster can be created
there. Human decision 2026-11-24: dev runs with `batch_compute = "ecs"` and no
instructor approval is required for that. ECS Fargate runs the same assembly
jars: the Hadoop MapReduce local runner (`mapreduce.framework.name=local`, s3a)
and Spark `--master local[*]`. EMR stays configurable (`batch_compute = "emr"`)
for an account without the SCP; prod may stay `emr`. Setting `"ecs"` removes
the EMR cluster, the EMR IAM roles and instance profile, the EMR security groups
and the deploy-role EMR statement (N77).

## Notes

- Keep `terraform.tfstate` from this directory somewhere safe. If it is lost,
  `terraform import` the bucket again; the bucket itself is protected by
  `prevent_destroy`.
- Removing the bucket is a manual, human-approved act and is outside
  `scripts/cleanup.sh`.
- Cost: pennies per month (a few KB of versioned state).
