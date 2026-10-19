#!/usr/bin/env bash
# Proves infra/terraform deploys: applies it to a throwaway LocalStack (never real AWS),
# checks the created resources, then deletes the LocalStack container (and with it every resource).
# `terraform destroy` is not run: against LocalStack 4.0.3 the AWS provider's destroy did not finish
# within 8 minutes, and deleting the container is a complete teardown anyway.
#
#   scripts/terraform-localstack.sh            # apply, verify, destroy
#   KEEP=1 scripts/terraform-localstack.sh     # leave LocalStack running for inspection
set -euo pipefail

cd "$(dirname "$0")/.."
TF_DIR=infra/terraform
PORT="${TF_LOCALSTACK_PORT:-4599}"
ENDPOINT="http://localhost:${PORT}"
CONTAINER=tf-localstack
STATE_DIR=$(mktemp -d)
STATE="${STATE_DIR}/localstack.tfstate"

cleanup() {
  if [[ "${KEEP:-0}" != "1" ]]; then
    docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
    rm -f "$STATE" "${STATE}.backup"
    rmdir "$STATE_DIR" 2> /dev/null || true
  fi
}
trap cleanup EXIT

echo "Starting a dedicated LocalStack on ${ENDPOINT}"
docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
docker run -d --name "$CONTAINER" -p "${PORT}:4566" \
  -e SERVICES=sqs,dynamodb,cloudwatch,sns,sts localstack/localstack:4.0.3 > /dev/null
for _ in $(seq 1 60); do
  curl -sf "${ENDPOINT}/_localstack/health" | grep -q '"sqs": "available"' && break
  sleep 2
done

# LocalStack starts its DynamoDB engine lazily; a slow first CreateTable makes the provider retry
# into "table already exists". Warm it (and SQS) up before applying.
docker exec "$CONTAINER" awslocal --region us-east-1 dynamodb list-tables > /dev/null
docker exec "$CONTAINER" awslocal --region us-east-1 sqs list-queues > /dev/null

terraform -chdir="$TF_DIR" fmt -check -recursive
terraform -chdir="$TF_DIR" init -input=false -backend=false > /dev/null
terraform -chdir="$TF_DIR" validate
terraform -chdir="$TF_DIR" apply -input=false -auto-approve -state="$STATE" -var "localstack_endpoint=${ENDPOINT}"

aws_() { docker exec "$CONTAINER" awslocal --region us-east-1 "$@"; }
echo
echo "=== Verifying deployed resources ==="
queues=$(aws_ sqs list-queues --query 'length(QueueUrls)' --output text)
tables=$(aws_ dynamodb list-tables --query 'length(TableNames)' --output text)
alarms=$(aws_ cloudwatch describe-alarms --query 'length(MetricAlarms)' --output text)
topics=$(aws_ sns list-topics --query 'length(Topics)' --output text)
redrive=$(aws_ sqs get-queue-attributes --queue-url "http://localhost:4566/000000000000/payment-queue" \
  --attribute-names RedrivePolicy --query 'Attributes.RedrivePolicy' --output text)
ttl=$(aws_ dynamodb describe-time-to-live --table-name idempotency_keys \
  --query 'TimeToLiveDescription.TimeToLiveStatus' --output text)
echo "queues=${queues} (expect 6) tables=${tables} (expect 4) alarms=${alarms} (expect 11) topics=${topics} (expect 1)"
echo "payment-queue RedrivePolicy: ${redrive}"
echo "idempotency_keys TTL: ${ttl}"
aws_ cloudwatch describe-alarms --query 'MetricAlarms[].[AlarmName, length(AlarmActions)]' --output text | sort

ok=1
[[ "$queues" == "6" && "$tables" == "4" && "$alarms" == "11" && "$topics" == "1" ]] || ok=0
[[ "$redrive" == *'"maxReceiveCount":5'* || "$redrive" == *'"maxReceiveCount":"5"'* ]] || ok=0
[[ "$ttl" == "ENABLED" ]] || ok=0

echo
if [[ "$ok" == "1" ]]; then
  echo "TERRAFORM LOCALSTACK DEPLOY VERIFIED (applied and checked; LocalStack container removed on exit)"
else
  echo "TERRAFORM LOCALSTACK DEPLOY CHECK FAILED"
  exit 1
fi
