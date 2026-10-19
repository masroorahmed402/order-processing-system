# Every alarm notifies the SNS topic on ALARM and on recovery (OK).
# Runbook for each alarm: README.md -> "Runbook".

# (a) Anything in a dead-letter queue needs a human.
resource "aws_cloudwatch_metric_alarm" "dlq_not_empty" {
  for_each = local.work_queues

  alarm_name          = "${each.key}-dlq-not-empty"
  alarm_description   = "Messages in ${each.key}-dlq: they failed ${var.max_receive_count} times or were malformed. See README Runbook: DLQ not empty."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.dlq[each.key].name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

# (b) Consumers are down or falling behind.
resource "aws_cloudwatch_metric_alarm" "queue_backlog_age" {
  for_each = local.work_queues

  alarm_name          = "${each.key}-oldest-message-age"
  alarm_description   = "Oldest message in ${each.key} is older than ${var.oldest_message_age_threshold_seconds}s. See README Runbook: queue backlog."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  dimensions          = { QueueName = aws_sqs_queue.work[each.key].name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.oldest_message_age_threshold_seconds
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}

# (c) Share of orders that failed in the last 5 minutes (Micrometer counters from order-service).
resource "aws_cloudwatch_metric_alarm" "order_failure_rate" {
  alarm_name          = "order-failure-rate-spike"
  alarm_description   = "More than ${var.order_failure_rate_threshold_percent}% of orders failed over 5 minutes. See README Runbook: order failure rate."
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.order_failure_rate_threshold_percent
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  metric_query {
    id          = "failure_rate"
    expression  = "IF(created >= ${var.order_failure_rate_min_orders}, 100 * FILL(failed, 0) / created, 0)"
    label       = "Failed orders (%)"
    return_data = true
  }

  metric_query {
    id = "failed"
    metric {
      namespace   = var.metrics_namespace
      metric_name = "orders.transitions.count"
      dimensions  = { status = "FAILED" }
      stat        = "Sum"
      period      = 300
    }
  }

  metric_query {
    id = "created"
    metric {
      namespace   = var.metrics_namespace
      metric_name = "orders.created.count"
      stat        = "Sum"
      period      = 300
    }
  }
}

# (d) Consumer exceptions (transient + permanent) per queue.
resource "aws_cloudwatch_metric_alarm" "consumer_failures" {
  for_each = local.work_queues

  alarm_name          = "${each.key}-consumer-failures-spike"
  alarm_description   = "Consumers of ${each.key} failed more than ${var.consumer_failure_threshold} times in 5 minutes. See README Runbook: consumer failures."
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.consumer_failure_threshold
  evaluation_periods  = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  metric_query {
    id          = "failures"
    expression  = "FILL(transient, 0) + FILL(permanent, 0)"
    label       = "Consumer failures"
    return_data = true
  }

  metric_query {
    id = "transient"
    metric {
      namespace   = var.metrics_namespace
      metric_name = "consumer.failures.count"
      dimensions  = { queue = each.key, kind = "transient" }
      stat        = "Sum"
      period      = 300
    }
  }

  metric_query {
    id = "permanent"
    metric {
      namespace   = var.metrics_namespace
      metric_name = "consumer.failures.count"
      dimensions  = { queue = each.key, kind = "permanent" }
      stat        = "Sum"
      period      = 300
    }
  }
}

# Extra: the outbox relay is stuck (orders are being accepted but not published).
resource "aws_cloudwatch_metric_alarm" "outbox_backlog" {
  alarm_name          = "order-outbox-backlog"
  alarm_description   = "More than ${var.outbox_backlog_threshold} unpublished outbox events for 5 minutes. See README Runbook: outbox backlog."
  namespace           = var.metrics_namespace
  metric_name         = "outbox.pending.value"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 5
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.outbox_backlog_threshold
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]
}
