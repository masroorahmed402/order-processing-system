#!/usr/bin/env bash
# LocalStack "ready" hook: creates the SQS queues (with DLQ redrive), the DynamoDB tables and
# seed stock used for local development and Testcontainers-based tests.
#
# Production resources are defined in Terraform (infra/terraform). Keep names in sync with
# com.example.orders.common.QueueNames and the table names in each service's config.
set -euo pipefail

REGION="${AWS_DEFAULT_REGION:-us-east-1}"
MAX_RECEIVE_COUNT=5
VISIBILITY_TIMEOUT=30

aws_() { awslocal --region "$REGION" "$@"; }

create_queue_with_dlq() {
  local name="$1"
  local dlq_url dlq_arn
  dlq_url=$(aws_ sqs create-queue --queue-name "${name}-dlq" \
    --attributes MessageRetentionPeriod=1209600 --query QueueUrl --output text)
  dlq_arn=$(aws_ sqs get-queue-attributes --queue-url "$dlq_url" \
    --attribute-names QueueArn --query Attributes.QueueArn --output text)
  aws_ sqs create-queue --queue-name "$name" --attributes "{
    \"VisibilityTimeout\": \"${VISIBILITY_TIMEOUT}\",
    \"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"${dlq_arn}\\\",\\\"maxReceiveCount\\\":\\\"${MAX_RECEIVE_COUNT}\\\"}\"
  }" > /dev/null
  echo "created queue ${name} (+ ${name}-dlq, maxReceiveCount=${MAX_RECEIVE_COUNT})"
}

create_table() {
  local table="$1" key="$2"
  aws_ dynamodb create-table --table-name "$table" \
    --attribute-definitions "AttributeName=${key},AttributeType=S" \
    --key-schema "AttributeName=${key},KeyType=HASH" \
    --billing-mode PAY_PER_REQUEST > /dev/null
  echo "created table ${table}"
}

seed_stock() {
  local sku="$1" stock="$2"
  aws_ dynamodb put-item --table-name inventory \
    --item "{\"sku\":{\"S\":\"${sku}\"},\"stock\":{\"N\":\"${stock}\"}}"
}

for q in inventory-queue payment-queue order-events-queue; do
  create_queue_with_dlq "$q"
done

create_table idempotency_keys idempotencyKey
aws_ dynamodb update-time-to-live --table-name idempotency_keys \
  --time-to-live-specification "Enabled=true,AttributeName=expiresAt" > /dev/null
create_table inventory sku
create_table inventory_reservations orderId
create_table payments orderId

seed_stock SKU-001 100
seed_stock SKU-002 50
seed_stock SKU-LOW 2
seed_stock SKU-LOAD 1000000
echo "seeded inventory"
echo "order-processing bootstrap complete"
