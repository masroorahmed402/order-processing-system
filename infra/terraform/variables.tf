variable "aws_region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Environment name, used in tags and the alarm topic name."
  type        = string
  default     = "dev"
}

variable "localstack_endpoint" {
  description = "Set to a LocalStack URL (e.g. http://localhost:4566) to deploy there instead of AWS."
  type        = string
  default     = null
}

variable "alarm_email" {
  description = "Optional email address subscribed to the alarm topic."
  type        = string
  default     = null
}

variable "metrics_namespace" {
  description = "CloudWatch namespace the services publish Micrometer metrics to."
  type        = string
  default     = "OrderProcessing"
}

variable "max_receive_count" {
  description = "Deliveries before SQS moves a message to the queue's DLQ."
  type        = number
  default     = 5
}

variable "queue_visibility_timeout_seconds" {
  description = "Default visibility timeout; consumers override it per message for backoff."
  type        = number
  default     = 30
}

variable "oldest_message_age_threshold_seconds" {
  description = "Alarm when the oldest message in a work queue is older than this."
  type        = number
  default     = 300
}

variable "order_failure_rate_threshold_percent" {
  description = "Alarm when more than this percentage of orders created in 5 minutes failed."
  type        = number
  default     = 20
}

variable "order_failure_rate_min_orders" {
  description = "Minimum orders in the 5-minute window before the failure-rate alarm can fire."
  type        = number
  default     = 20
}

variable "consumer_failure_threshold" {
  description = "Alarm when a queue's consumers fail more than this many times in 5 minutes."
  type        = number
  default     = 25
}

variable "outbox_backlog_threshold" {
  description = "Alarm when this many outbox events are waiting to be published for 5 minutes."
  type        = number
  default     = 1000
}
