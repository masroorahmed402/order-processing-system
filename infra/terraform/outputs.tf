output "queue_urls" {
  description = "Work queue URLs by name."
  value       = { for name, q in aws_sqs_queue.work : name => q.url }
}

output "dlq_urls" {
  description = "Dead-letter queue URLs by work queue name."
  value       = { for name, q in aws_sqs_queue.dlq : name => q.url }
}

output "dynamodb_tables" {
  description = "DynamoDB table names."
  value       = sort([for t in aws_dynamodb_table.this : t.name])
}

output "alarm_topic_arn" {
  description = "SNS topic every alarm publishes to."
  value       = aws_sns_topic.alarms.arn
}

output "alarm_names" {
  description = "All CloudWatch alarms."
  value = sort(concat(
    [for a in aws_cloudwatch_metric_alarm.dlq_not_empty : a.alarm_name],
    [for a in aws_cloudwatch_metric_alarm.queue_backlog_age : a.alarm_name],
    [for a in aws_cloudwatch_metric_alarm.consumer_failures : a.alarm_name],
    [aws_cloudwatch_metric_alarm.order_failure_rate.alarm_name, aws_cloudwatch_metric_alarm.outbox_backlog.alarm_name],
  ))
}
