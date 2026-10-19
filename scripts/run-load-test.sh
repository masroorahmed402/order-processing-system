#!/usr/bin/env bash
# One command for the whole load test:
#   build -> fresh infra + services (docker compose) -> k6 (1,000 concurrent orders + retries)
#   -> reconciliation -> save results -> shut everything down.
#
# Environment:
#   PAYMENT_TRANSIENT_FAILURE_RATE  injected payment gateway failures (default 0.05 = 5%)
#   RECONCILE_TIMEOUT               seconds to wait for all orders to finish (default 300)
#   KEEP_RUNNING=1                  leave the stack up afterwards for inspection
#   SKIP_BUILD=1                    reuse already built jars
#   K6_MODE=docker|host             where k6 runs (default docker, see below)
#
# By default k6 runs in a container on the compose network (like a load generator inside the
# same VPC as the service). On Docker Desktop for Mac the host->container port forwarder resets
# a large share of connections when 1,000 arrive at once against a cold JVM (measured 24-47%;
# 0% from inside the network), which would test the laptop's port proxy rather than the system.
# K6_MODE=host uses the locally installed k6 against localhost:8081 instead.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
RESULTS="${ROOT}/load-test/results/${STAMP}"
RUN_ID="${STAMP}"
SKU="SKU-LOAD"
K6_MODE="${K6_MODE:-docker}"
K6_IMAGE="grafana/k6:2.0.0"
export PAYMENT_TRANSIENT_FAILURE_RATE="${PAYMENT_TRANSIENT_FAILURE_RATE:-0.05}"
mkdir -p "$RESULTS"

log() { printf '\n[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

cleanup() {
  docker compose --profile apps logs --no-color > "${RESULTS}/services.log" 2>&1 || true
  if [[ "${KEEP_RUNNING:-0}" != "1" ]]; then
    log "Shutting down"
    docker compose --profile apps down -v > /dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# 1,000 VUs need well over the macOS default of 256 open files.
ulimit -n "$(ulimit -Hn 2>/dev/null | sed 's/unlimited/65536/')" 2>/dev/null || ulimit -n 65536 2>/dev/null || true

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  log "Building service jars"
  mvn -B -q -DskipTests clean package
fi

log "Starting fresh infrastructure and services (payment transient failure rate ${PAYMENT_TRANSIENT_FAILURE_RATE})"
docker compose --profile apps down -v > /dev/null 2>&1 || true
docker compose --profile apps up -d --build --wait

STOCK_BEFORE=$(docker compose exec -T localstack awslocal --region us-east-1 dynamodb get-item \
  --table-name inventory --consistent-read --key "{\"sku\":{\"S\":\"${SKU}\"}}" \
  --query 'Item.stock.N' --output text)
log "Stock of ${SKU} before the run: ${STOCK_BEFORE}"

{
  echo "run_id=${RUN_ID}"
  echo "date_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "payment_transient_failure_rate=${PAYMENT_TRANSIENT_FAILURE_RATE}"
  echo "stock_before=${STOCK_BEFORE}"
  echo "os=$(uname -srm)"
  if [[ "$(uname)" == "Darwin" ]]; then
    echo "cpu=$(sysctl -n machdep.cpu.brand_string) ($(sysctl -n hw.ncpu) cores)"
    echo "memory_gb=$(( $(sysctl -n hw.memsize) / 1024 / 1024 / 1024 ))"
  else
    echo "cpu=$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | xargs) ($(nproc) cores)"
    echo "memory_gb=$(( $(grep MemTotal /proc/meminfo | awk '{print $2}') / 1024 / 1024 ))"
  fi
  echo "docker=$(docker version --format '{{.Server.Version}}') cpus=$(docker info --format '{{.NCPU}}') mem_gb=$(( $(docker info --format '{{.MemTotal}}') / 1024 / 1024 / 1024 ))"
  echo "k6_mode=${K6_MODE}"
  if [[ "$K6_MODE" == "host" ]]; then
    echo "k6=$(k6 version | head -1)"
  else
    echo "k6=$(docker run --rm "$K6_IMAGE" version | head -1) (${K6_IMAGE}, on the compose network)"
  fi
} > "${RESULTS}/environment.txt"

log "Running k6 (${K6_MODE}): 1,000 VUs x 1 iteration (create + immediate retry)"
set +e
if [[ "$K6_MODE" == "host" ]]; then
  k6 run --env BASE_URL=http://localhost:8081 --env RUN_ID="${RUN_ID}" --env SKU="${SKU}" \
    --summary-export "${RESULTS}/k6-summary.json" load-test/k6-orders.js 2>&1 | tee "${RESULTS}/k6.txt"
  K6_EXIT=${PIPESTATUS[0]}
else
  docker run --rm --network order-processing_default --ulimit nofile=65536:65536 \
    --user "$(id -u):$(id -g)" \
    -v "${ROOT}/load-test:/scripts:ro" -v "${RESULTS}:/results" \
    "$K6_IMAGE" run --env BASE_URL=http://order-service:8081 --env RUN_ID="${RUN_ID}" --env SKU="${SKU}" \
    --summary-export /results/k6-summary.json /scripts/k6-orders.js 2>&1 | tee "${RESULTS}/k6.txt"
  K6_EXIT=${PIPESTATUS[0]}
fi

log "Reconciling"
load-test/reconcile.sh "${RUN_ID}" "${STOCK_BEFORE}" 1000 "${SKU}" "${RECONCILE_TIMEOUT:-300}" 2>&1 | tee "${RESULTS}/reconcile.txt"
RECONCILE_EXIT=${PIPESTATUS[0]}
set -e

# Evidence that metrics reach CloudWatch (LocalStack): wait one 30s publish step, then list them.
sleep 35
docker compose exec -T localstack awslocal --region us-east-1 cloudwatch list-metrics --namespace OrderProcessing \
  --output json 2>/dev/null | jq -r '.Metrics[] | "\(.MetricName) \([(.Dimensions // [])[] | "\(.Name)=\(.Value)"] | join(","))"' \
  | grep -E '^(orders|consumer|payments|inventory|outbox)\.' | sort -u > "${RESULTS}/cloudwatch-metrics.txt" || true

log "Results saved in ${RESULTS} (k6 exit ${K6_EXIT}, reconcile exit ${RECONCILE_EXIT})"
if [[ $K6_EXIT -ne 0 || $RECONCILE_EXIT -ne 0 ]]; then
  echo "LOAD TEST FAILED"
  exit 1
fi
echo "LOAD TEST PASSED"
