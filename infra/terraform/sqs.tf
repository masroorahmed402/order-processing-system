locals {
  # Must match com.example.orders.common.QueueNames.
  work_queues = toset(["inventory-queue", "payment-queue", "order-events-queue"])
}

resource "aws_sqs_queue" "dlq" {
  for_each = local.work_queues

  name                      = "${each.key}-dlq"
  message_retention_seconds = 1209600 # 14 days: time to investigate and redrive
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "work" {
  for_each = local.work_queues

  name                       = each.key
  visibility_timeout_seconds = var.queue_visibility_timeout_seconds
  receive_wait_time_seconds  = 20 # long polling
  message_retention_seconds  = 345600
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = var.max_receive_count
  })
}

# Only the matching work queue may use each DLQ (also what the console redrive uses).
resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  for_each = local.work_queues

  queue_url = aws_sqs_queue.dlq[each.key].id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.work[each.key].arn]
  })
}
