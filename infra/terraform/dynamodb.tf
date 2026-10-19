locals {
  # table name => partition key
  tables = {
    idempotency_keys       = "idempotencyKey"
    inventory              = "sku"
    inventory_reservations = "orderId"
    payments               = "orderId"
  }
}

resource "aws_dynamodb_table" "this" {
  for_each = local.tables

  name         = each.key
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = each.value

  attribute {
    name = each.value
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  # Encrypted at rest with an AWS-owned key by default. To use a customer-managed KMS key add
  # server_side_encryption { enabled = true, kms_key_arn = ... } (needs KMS, so not on LocalStack).

  dynamic "ttl" {
    for_each = each.key == "idempotency_keys" ? [1] : []
    content {
      attribute_name = "expiresAt"
      enabled        = true
    }
  }
}
