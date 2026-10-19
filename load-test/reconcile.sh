#!/usr/bin/env bash
# Verifies the system's invariants after a load test run (scripts/run-load-test.sh calls this).
#
# Usage: load-test/reconcile.sh <run-id> <stock-before> [expected-orders] [sku] [timeout-seconds]
#
# Waits until every order of the run reaches CONFIRMED/FAILED (or the timeout), then checks:
#   1. total orders == expected
#   2. no idempotency key produced more than one order
#   3. no order is stuck in a non-terminal state
#   4. CONFIRMED + FAILED == expected
#   5. stock decrease == sum of CONFIRMED quantities
#   6. payment rows == orders that reached payment (and none stuck in CHARGING)
#   7. every DLQ is empty
#   8. every outbox row was published
# Exits non-zero if any check fails.
set -euo pipefail

RUN_ID="${1:?run id required}"
STOCK_BEFORE="${2:?stock before required}"
EXPECTED="${3:-1000}"
SKU="${4:-SKU-LOAD}"
TIMEOUT="${5:-300}"
KEY_PATTERN="load-${RUN_ID}-%"

cd "$(dirname "$0")/.."

sql() {
  docker compose exec -T postgres psql -U orders -d orders -tA -v ON_ERROR_STOP=1 -c "$1" | tr -d '[:space:]'
}
aws_() {
  docker compose exec -T localstack awslocal --region us-east-1 "$@"
}
queue_depth() {
  local url
  url="http://localhost:4566/000000000000/$1"
  aws_ sqs get-queue-attributes --queue-url "$url" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --query '[Attributes.ApproximateNumberOfMessages, Attributes.ApproximateNumberOfMessagesNotVisible]' \
    --output text | awk '{print $1 + $2}'
}

echo "Reconciling run ${RUN_ID} (expecting ${EXPECTED} orders on ${SKU}, timeout ${TIMEOUT}s)"
start=$(date +%s)
last_total=-1
stable_since=$start
while true; do
  total=$(sql "select count(*) from orders where idempotency_key like '${KEY_PATTERN}'")
  pending=$(sql "select count(*) from orders where idempotency_key like '${KEY_PATTERN}' and status not in ('CONFIRMED','FAILED')")
  elapsed=$(( $(date +%s) - start ))
  if [[ "$pending" == "0" && "$total" -ge "$EXPECTED" ]]; then
    echo "All ${total} orders terminal after ${elapsed}s of waiting"
    break
  fi
  if [[ "$total" != "$last_total" ]]; then
    last_total=$total
    stable_since=$(date +%s)
  elif [[ "$pending" == "0" ]] && (( $(date +%s) - stable_since >= 15 )); then
    echo "Nothing pending and no new orders for 15s; stopping the wait with ${total} orders"
    break
  fi
  if (( elapsed >= TIMEOUT )); then
    echo "Timed out after ${elapsed}s with ${pending} non-terminal orders"
    break
  fi
  echo "  waiting: ${total} orders, ${pending} not terminal yet (${elapsed}s)"
  sleep 2
done
# Give in-flight duplicate/compensation messages a moment to drain before checking queues.
sleep 3

total=$(sql "select count(*) from orders where idempotency_key like '${KEY_PATTERN}'")
duplicate_keys=$(sql "select count(*) from (select idempotency_key from orders where idempotency_key like '${KEY_PATTERN}' group by idempotency_key having count(*) > 1) d")
pending=$(sql "select count(*) from orders where idempotency_key like '${KEY_PATTERN}' and status not in ('CONFIRMED','FAILED')")
confirmed=$(sql "select count(*) from orders where idempotency_key like '${KEY_PATTERN}' and status = 'CONFIRMED'")
failed=$(sql "select count(*) from orders where idempotency_key like '${KEY_PATTERN}' and status = 'FAILED'")
confirmed_qty=$(sql "select coalesce(sum(i.quantity),0) from orders o join order_items i on i.order_id = o.id where o.idempotency_key like '${KEY_PATTERN}' and o.status = 'CONFIRMED' and i.sku = '${SKU}'")
reached_payment=$(sql "select count(*) from orders where idempotency_key like '${KEY_PATTERN}' and payment_status in ('COMPLETED','FAILED')")
unpublished_outbox=$(sql "select count(*) from outbox_events where published_at is null")

stock_after=$(aws_ dynamodb get-item --table-name inventory --consistent-read \
  --key "{\"sku\":{\"S\":\"${SKU}\"}}" --query 'Item.stock.N' --output text)
stock_decrease=$(( STOCK_BEFORE - stock_after ))
payment_rows=$(aws_ dynamodb scan --table-name payments --select COUNT --query Count --output text)
charging_rows=$(aws_ dynamodb scan --table-name payments --select COUNT \
  --filter-expression '#s = :c' --expression-attribute-names '{"#s":"status"}' \
  --expression-attribute-values '{":c":{"S":"CHARGING"}}' --query Count --output text)

dlq_total=0
dlq_report=""
for q in inventory-queue-dlq payment-queue-dlq order-events-queue-dlq; do
  depth=$(queue_depth "$q")
  dlq_total=$(( dlq_total + depth ))
  dlq_report="${dlq_report} ${q}=${depth}"
done

failures=0
report() {
  local name="$1" actual="$2" expected="$3"
  if [[ "$actual" == "$expected" ]]; then
    printf '  PASS  %-48s %s\n' "$name" "$actual"
  else
    printf '  FAIL  %-48s %s (expected %s)\n' "$name" "$actual" "$expected"
    failures=$((failures + 1))
  fi
}

echo
echo "=== Reconciliation: run ${RUN_ID} ==="
echo "  orders: total=${total} confirmed=${confirmed} failed=${failed} non-terminal=${pending}"
echo "  stock ${SKU}: before=${STOCK_BEFORE} after=${stock_after} decrease=${stock_decrease}"
echo "  payments table rows=${payment_rows} (CHARGING=${charging_rows}); orders that reached payment=${reached_payment}"
echo "  DLQ depths:${dlq_report}"
echo
report "total orders == ${EXPECTED}" "$total" "$EXPECTED"
report "orders sharing an idempotency key" "$duplicate_keys" "0"
report "orders not in a terminal state (lost/stuck)" "$pending" "0"
report "CONFIRMED + FAILED == ${EXPECTED}" "$(( confirmed + failed ))" "$EXPECTED"
report "stock decrease == sum of CONFIRMED quantities" "$stock_decrease" "$confirmed_qty"
report "payment rows == orders that reached payment" "$payment_rows" "$reached_payment"
report "payments stuck in CHARGING" "$charging_rows" "0"
report "messages in all DLQs" "$dlq_total" "0"
report "unpublished outbox rows" "$unpublished_outbox" "0"
echo
if (( failures > 0 )); then
  echo "RECONCILIATION FAILED: ${failures} check(s) failed"
  exit 1
fi
echo "RECONCILIATION PASSED: all checks green"
