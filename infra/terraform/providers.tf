locals {
  use_localstack = var.localstack_endpoint != null
}

provider "aws" {
  region = var.aws_region

  # Only for LocalStack, which accepts any credentials. Real AWS uses the normal credential chain.
  access_key                  = local.use_localstack ? "test" : null
  secret_key                  = local.use_localstack ? "test" : null
  skip_credentials_validation = local.use_localstack
  skip_requesting_account_id  = local.use_localstack
  skip_metadata_api_check     = local.use_localstack

  dynamic "endpoints" {
    for_each = local.use_localstack ? [var.localstack_endpoint] : []
    content {
      cloudwatch = endpoints.value
      dynamodb   = endpoints.value
      sns        = endpoints.value
      sqs        = endpoints.value
      sts        = endpoints.value
    }
  }

  default_tags {
    tags = {
      Project     = "order-processing"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
