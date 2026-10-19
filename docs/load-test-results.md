# Load test results

Real output from `scripts/run-load-test.sh`, copied from `load-test/results/<run>/` (k6 summary,
reconciliation, environment). Nothing here is rounded or estimated.

## Setup

| | |
|---|---|
| Date | 2026-10-19 (UTC timestamps in the run ids) |
| Machine | Apple M4, 10 cores, 16 GB RAM, macOS (Darwin 27.0.0, arm64) |
| Docker | Docker Desktop, engine 29.5.2, 10 CPUs and 7 GB RAM available to containers |
| Stack | Postgres 16.4, LocalStack 4.0.3 (SQS, DynamoDB, CloudWatch), order/inventory/payment services in Docker (eclipse-temurin 21 JRE), all on one laptop |
| Load generator | k6 v2.0.0 (`grafana/k6:2.0.0`) on the compose network, see the note below |
| Scenario | `shared-iterations`, **1,000 VUs, 1,000 iterations**; each iteration POSTs an order with a unique Idempotency-Key and immediately retries the same request (2,000 requests) |
| SKU | `SKU-LOAD`, 1,000,000 units of stock, 1 unit per order at 9.99 |
| Fault injection | payment gateway transient failure rate **5%** (`PAYMENT_TRANSIENT_FAILURE_RATE=0.05`) |

## Final runs (two consecutive, from a clean stack)

| | Run 1 (`20261019T184458Z`) | Run 2 (`20261019T184711Z`) |
|---|---|---|
| k6 checks passed | 3000 / 3000 (100.00%) | 3000 / 3000 (100.00%) |
| first call 201 / retry 200 / same order id | 1000 / 1000 / 1000 | 1000 / 1000 / 1000 |
| `http_req_failed` | 0.00% (0 of 2000) | 0.00% (0 of 2000) |
| Requests | 2000 at 94.09 req/s | 2000 at 209.49 req/s |
| Iterations (orders) | 1000 at 47.05/s | 1000 at 104.74/s |
| `http_req_duration` avg | 9.85s | 4.03s |
| med | 9.21s | 3.59s |
| p90 | 16.91s | 6.65s |
| **p95** | **18.62s** | **7.39s** |
| **p99** | **19.45s** | **7.74s** |
| max | 20.1s | 8.47s |
| k6 duration | 21.3s | 9.5s |
| Saga drain after k6 (all 1,000 terminal) | 29s | 16s |
| Injected transient payment failures (retried with backoff) | 47 | 49 |
| Highest receive count seen | 2 | 2 |

Run 1 was markedly slower than run 2 and than the two earlier passing runs during development
(p95 7.46s and 7.37s). I did not establish why (it was the first run after the full
`mvn clean verify`). Both runs are reported as measured.

### Reconciliation, run 2 (verbatim)

```
=== Reconciliation: run 20261019T184711Z ===
  orders: total=1000 confirmed=1000 failed=0 non-terminal=0
  stock SKU-LOAD: before=1000000 after=999000 decrease=1000
  payments table rows=1000 (CHARGING=0); orders that reached payment=1000
  DLQ depths: inventory-queue-dlq=0 payment-queue-dlq=0 order-events-queue-dlq=0

  PASS  total orders == 1000                             1000
  PASS  orders sharing an idempotency key                0
  PASS  orders not in a terminal state (lost/stuck)      0
  PASS  CONFIRMED + FAILED == 1000                       1000
  PASS  stock decrease == sum of CONFIRMED quantities    1000
  PASS  payment rows == orders that reached payment      1000
  PASS  payments stuck in CHARGING                       0
  PASS  messages in all DLQs                             0
  PASS  unpublished outbox rows                          0

RECONCILIATION PASSED: all checks green
```

Run 1 reconciliation, verbatim:

```
=== Reconciliation: run 20261019T184458Z ===
  orders: total=1000 confirmed=1000 failed=0 non-terminal=0
  stock SKU-LOAD: before=1000000 after=999000 decrease=1000
  payments table rows=1000 (CHARGING=0); orders that reached payment=1000
  DLQ depths: inventory-queue-dlq=0 payment-queue-dlq=0 order-events-queue-dlq=0

  PASS  total orders == 1000                             1000
  PASS  orders sharing an idempotency key                0
  PASS  orders not in a terminal state (lost/stuck)      0
  PASS  CONFIRMED + FAILED == 1000                       1000
  PASS  stock decrease == sum of CONFIRMED quantities    1000
  PASS  payment rows == orders that reached payment      1000
  PASS  payments stuck in CHARGING                       0
  PASS  messages in all DLQs                             0
  PASS  unpublished outbox rows                          0

RECONCILIATION PASSED: all checks green
```

### k6 summary (run 2, verbatim)

```
  █ THRESHOLDS 
    checks
    ✓ 'rate==1.0' rate=100.00%
    http_req_failed
    ✓ 'rate==0' rate=0.00%
  █ TOTAL RESULTS 
    checks_total.......: 3000    314.233069/s
    checks_succeeded...: 100.00% 3000 out of 3000
    checks_failed......: 0.00%   0 out of 3000
    ✓ first call is 201
    ✓ retry is 200
    ✓ retry returns the same order id
    HTTP
    http_req_duration..............: avg=4.03s min=87.82ms  med=3.59s max=8.47s p(90)=6.65s p(95)=7.39s p(99)=7.74s
      { expected_response:true }...: avg=4.03s min=87.82ms  med=3.59s max=8.47s p(90)=6.65s p(95)=7.39s p(99)=7.74s
    http_req_failed................: 0.00%  0 out of 2000
    http_reqs......................: 2000   209.488713/s
    EXECUTION
    iteration_duration.............: avg=8.13s min=941.07ms med=8.5s  max=9.53s p(90)=9.48s p(95)=9.5s  p(99)=9.52s
    iterations.....................: 1000   104.744356/s
    vus............................: 291    min=291       max=1000
    vus_max........................: 1000   min=1000      max=1000
    NETWORK
    data_received..................: 1.0 MB 105 kB/s
    data_sent......................: 540 kB 57 kB/s
```

### CloudWatch metrics present after the run (LocalStack `ListMetrics`, namespace `OrderProcessing`)

```
consumer.failures.count kind=transient,queue=payment-queue
consumer.processing.latency.avg queue=inventory-queue
consumer.processing.latency.avg queue=order-events-queue
consumer.processing.latency.avg queue=payment-queue
consumer.processing.latency.count queue=inventory-queue
consumer.processing.latency.count queue=order-events-queue
consumer.processing.latency.count queue=payment-queue
consumer.processing.latency.max queue=inventory-queue
consumer.processing.latency.max queue=order-events-queue
consumer.processing.latency.max queue=payment-queue
consumer.processing.latency.sum queue=inventory-queue
consumer.processing.latency.sum queue=order-events-queue
consumer.processing.latency.sum queue=payment-queue
inventory.reservations.count outcome=reserved
orders.created.count 
orders.saga.duration.avg status=CONFIRMED
orders.saga.duration.count status=CONFIRMED
orders.saga.duration.max status=CONFIRMED
orders.saga.duration.sum status=CONFIRMED
orders.transitions.count status=CONFIRMED
orders.transitions.count status=INVENTORY_RESERVED
outbox.pending.value 
outbox.publish.failures.count 
outbox.published.count 
payments.processed.count outcome=completed
payments.processed.count outcome=transient_failure
```

## What the numbers mean

- **Correctness held under concurrency and duplication**: 2,000 requests, 0 errors, every retry
  returned the original order, 0 duplicate orders, 0 lost or stuck orders, stock and payments
  reconcile exactly, all DLQs empty. That held even with 5% of payment attempts failing: every
  injected failure was retried with backoff (no message needed more than 2 receives) and confirmed.
- **Latency is bounded by LocalStack, not the services.** During a burst, LocalStack (one Python
  process) ran at 122–144% CPU while order-service averaged ~600 ms of server-side time per
  `POST /orders` (Spring `http.server.requests`: 2000 requests, 1,197,730 ms total, max 2,247 ms).
  The rest of the client-observed latency is queueing behind 200 busy Tomcat threads that are all
  waiting on LocalStack. Each order costs roughly 15 SQS/DynamoDB calls across the three services,
  all served by that one process. Real SQS and DynamoDB do not have this ceiling, so treat these
  latencies as a pessimistic local figure, not a production estimate.

## Issues found and fixed on the way to a clean pass

| Run | Result | Root cause | Fix |
|---|---|---|---|
| `20261019T180407Z` | build failed | iCloud Drive created `* 2.class` conflict copies in `target/`; Boot repackage found two main classes | runner uses `mvn clean package` (keep the repo outside iCloud) |
| `20261019T180538Z` | **783/1000** orders; 450 requests failed with `connection reset by peer` (k6 on the host) | Cold 1,000-connection burst through Docker Desktop's macOS port forwarder. A bare `/actuator/health` burst failed 46.7% cold vs 0% warm, and **0% cold from inside the Docker network**. The 783 orders that got in all reconciled correctly. | `somaxconn`/`tcp_max_syn_backlog` 4096 on the order-service container, Tomcat `accept-count` 4096 and pre-started threads (cut the host-side failures to 24.4%), and k6 on the compose network (`K6_MODE=host` still available). No check or count was relaxed. |
| `20261019T181437Z`, `20261019T181610Z` | **passed** (p95 7.46s, 7.37s) | | |
| `20261019T184458Z`, `20261019T184711Z` | **passed**, final runs above | | |
| `20261019T185447Z` | **passed**: re-verification after the last code change (decline limit quoted in config): 3000/3000 checks, 0 of 2000 failed, 190.46 req/s, p95 8.23s, p99 8.51s, all 1,000 terminal 19s after k6, 50 injected payment failures retried, reconciliation all green | | |
