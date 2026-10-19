terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
  }

  # Production: configure a remote backend, e.g.
  #   backend "s3" { bucket = "...", key = "order-processing/terraform.tfstate", dynamodb_table = "..." }
  # Local runs against LocalStack (scripts/terraform-localstack.sh) use a throwaway local state.
}
