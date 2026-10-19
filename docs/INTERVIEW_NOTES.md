# Interview notes: Order Processing Microservices

A plain-English walkthrough of the system, the decisions behind it, the bugs that came up while
building it, the measured results, and questions you are likely to be asked.

---

## 1. How the system works

A customer places an order. Three independent services need to agree on what happens: the
**order service** records the order, the **inventory service** holds the stock, and the
**payment service** takes the money. They never call each other; they only exchange messages
over **Amazon SQS** (LocalStack locally).

1. **Order placed.** `POST /orders` arrives with an `Idempotency-Key` header. The order service
   first "claims" the key in a DynamoDB table with a conditional write that only succeeds if the
   key is new. The claim also stores the order id this key will get and a hash of the request
   body. Then, in **one Postgres transaction**, it inserts the order (`PENDING`) and an
   `ORDER_CREATED` row in an **outbox** table, and replies `201`.
2. **Outbox relay.** A scheduled job reads unpublished outbox rows (`FOR UPDATE SKIP LOCKED`),
   sends them to `inventory-queue`, and marks them published.
3. **Stock.** The inventory service receives `ORDER_CREATED` and runs one DynamoDB
   `TransactWriteItems`: write a "reservation" marker for this order (only if none exists) and
   decrement each SKU (only if there is enough). Either both happen or neither. On success it
   sends `INVENTORY_RESERVED` to `payment-queue` and `order-events-queue`; otherwise it records a
   rejection and sends `INVENTORY_FAILED` to `order-events-queue`.
4. **Payment.** The payment service receives `INVENTORY_RESERVED`, claims the order in its
   `payments` table (conditional write), calls the `PaymentGateway` with the order id as the
   gateway idempotency key, stores the result, and sends `PAYMENT_COMPLETED` or `PAYMENT_FAILED`.
5. **Order outcome.** The order service consumes `order-events-queue` and updates the order:
   `PENDING → INVENTORY_RESERVED → CONFIRMED`, or `FAILED`. If payment failed, it marks the
   order `FAILED` and, in the same transaction, writes an `INVENTORY_RELEASE` outbox event. That
   is the **compensating transaction**: inventory gives the stock back exactly once.
6. `GET /orders/{id}` shows the result.

Everything is designed for **at-least-once, unordered delivery**: any message can arrive twice,
late or before the message it logically follows, and the system still ends in the right state.

When something fails temporarily (gateway timeout, database conflict), the consumer does not
acknowledge the message and pushes its next delivery out exponentially (2 s, 4 s, 8 s, 16 s).
After 5 attempts SQS moves it to a **dead-letter queue**, which raises a CloudWatch alarm.
Messages that can never succeed (malformed JSON) skip the retries and go straight to the DLQ.

## 2. Design decisions and why

| Decision | Why | Trade-off |
|---|---|---|
| **Choreographed saga** over 2PC | Distributed transactions across Postgres, DynamoDB and a card processor are impossible or fragile; a saga gives eventual consistency with explicit compensation. | The system is briefly inconsistent (stock reserved, payment pending); the flow is spread across services. |
| **Choreography** over orchestration | Three steps, one compensation: no coordinator to build, deploy or scale; services stay decoupled. | Harder to see the whole flow in one place; with more steps, Step Functions or Temporal would win. |
| **Transactional outbox** | Avoids the dual-write problem (DB commit succeeds but the SQS send fails, or the reverse). The order and its event commit atomically. | Up to ~100 ms extra latency and at-least-once publishing, so consumers must dedupe (they do anyway). |
| **Idempotency key → pre-assigned order id** | The DynamoDB claim fixes which order id a key maps to *before* the insert, and that id is the Postgres primary key. Racing duplicates collide on the PK instead of creating two orders. | Two stores involved in one request; a crash between claim and insert leaves a claim that is taken over after 30 s. |
| **Fingerprint the request body** | Reusing a key for a different order is a client bug; returning the old order would hide it, so it gets `422`. | Fingerprint must be canonical (price `10.0` = `10.00`). |
| **Dedupe marker + stock in one DynamoDB transaction** | Makes "reserved" and "marker written" inseparable, so a retry can never decrement twice. | Transactions cost 2× write capacity and can conflict on hot items, which is handled with jittered in-process retries. |
| **Store outcomes, replay them** | A duplicate re-publishes the stored decision instead of re-deciding, so redelivery after a crash completes the publish and never flips a decision (e.g. stock replenished later). | Extra marker rows (cheap). |
| **Payment claim + gateway idempotency key** | The claim prevents two consumers calling the gateway concurrently; the idempotency key protects the takeover case after a crash mid-charge. | Needs a gateway that supports idempotency keys (Stripe, Adyen and others do). |
| **Order tracks inventory and payment separately** | Order of arrival stops mattering: `CONFIRMED` once both are in. | Slightly richer model than a single status enum. |
| **Optimistic locking (`@Version`)** | `INVENTORY_RESERVED` and `PAYMENT_COMPLETED` for one order often arrive together; lost updates would drop one of them. | Conflicts need retries: 5 in-process attempts, then SQS backoff. |
| **Exponential backoff via visibility timeout** | SQS has no native per-message backoff; changing the visibility on failure implements it without extra infrastructure. | The delay is approximate (LocalStack added roughly 1 s per step in tests). |
| **Permanent errors → DLQ immediately** | Retrying a malformed message 5 times only delays the alarm. | Classification must be right; anything unknown defaults to transient (safer). |
| **Postgres for orders, DynamoDB for the rest** | Orders are relational and queried flexibly, and need transactions with the outbox. Inventory, payments and keys are single-key, high-contention check-and-set: DynamoDB conditional writes are a natural fit and scale without connection pools. | Two databases to run; no cross-store transactions (not needed with this design). |
| **SQS standard, not FIFO** | Higher throughput, and idempotent consumers are needed anyway (redrives, replays). | No ordering guarantees, which the state machine handles. |

## 3. The hardest bugs and how they were fixed

1. **Outbox silently stuck: "payload must be instance of String: [B".** The outbox relay kept
   logging a warning every 100 ms and no message reached SQS. Spring's `StringMessageConverter`
   serializes to `byte[]` by default, but Spring Cloud AWS's SQS converter requires a `String`
   payload. Fix: `setSerializedPayloadClass(String.class)` on the converters, plus an
   `outbox.publish.failures` counter and an outbox-backlog alarm, so this failure mode is now
   visible instead of a log line. Lesson: a component that "retries forever" needs a metric.
2. **Spring Cloud AWS refused to start: "ObjectMapper can only be set in
   MappingJackson2MessageConverter instances".** I wanted raw-string payloads so that malformed
   JSON is classified by our codec (as a permanent error) instead of failing inside the framework.
   The auto-configuration injects an ObjectMapper into whatever converter it finds. Fix: a
   composite converter with a `StringMessageConverter` first and a Jackson converter second, and
   a payload type mapper that always says `String`.
3. **217 of 1,000 load-test orders never arrived ("connection reset by peer").** All of them
   failed at TCP connect. I isolated it with a bare `/actuator/health` burst: 46.7% failures
   against a cold JVM through Docker Desktop's port forwarder, 0% once warm, and **0% for the
   same cold burst from inside the Docker network**. Raising the container's `somaxconn` and
   `tcp_max_syn_backlog` to 4096, Tomcat `accept-count` to 4096 and pre-starting Tomcat threads
   cut it to 24.4%; the rest was the macOS port proxy, not the service. The load generator now
   runs on the compose network (like a load generator inside the VPC), with `K6_MODE=host`
   available. The fix never touched the checks or the order count.
4. **Terraform: "Table already exists" on an empty LocalStack.** I bisected the table options:
   `server_side_encryption { enabled = true }` asks for a KMS-managed key; KMS wasn't running, so
   `CreateTable` failed and the provider retried, which then collided with the half-created table.
   DynamoDB already encrypts at rest with AWS-owned keys, so the block was removed (customer
   managed keys documented as an option). `terraform destroy` also hung against LocalStack 4.0.3,
   so the verification script tears down by deleting the throwaway container.
5. **DLQ reason said "NullPointerException: eventId is required".** The DLQ attribute showed the
   root cause rather than the classification, which is useless to an operator. `describe()` now
   prefers the `PermanentMessageException` in the cause chain.
6. **Duplicate classes named "… 2.class".** The project sits in an iCloud-synced folder, which
   creates conflict copies of build outputs, so the Boot repackager found two main classes. The
   load-test runner uses `mvn clean package`; the real fix is keeping the repository outside
   iCloud Drive.
7. **Empty aggregate coverage report.** `jacoco:report-aggregate` ignores test-scoped
   dependencies, and only looks at direct dependencies, so the e2e module depends on all modules
   in compile scope.

## 4. Real load test results

Machine: Apple M4, 10 cores, 16 GB, Docker Desktop (10 CPUs, 7 GB). Everything local:
Postgres, LocalStack, three services in Docker, k6 v2.0.0 on the compose network. Scenario:
1,000 VUs × 1 iteration, each = create order + immediate retry with the same key (2,000
requests), 5% injected transient payment failures. Two consecutive final runs on 2026-10-19:

| | Run 1 | Run 2 |
|---|---:|---:|
| HTTP errors | 0 of 2000 | 0 of 2000 |
| Checks | 3000/3000 | 3000/3000 |
| Throughput | 94.09 req/s | 209.49 req/s |
| p95 / p99 | 18.62 s / 19.45 s | 7.39 s / 7.74 s |
| All 1,000 terminal after k6 | +29 s | +16 s |

Reconciliation, both runs: 1,000 orders, 1,000 CONFIRMED, 0 duplicates per key, 0 stuck, stock
−1,000 = confirmed quantity, 1,000 payment rows = orders that reached payment, 0 `CHARGING`, all
DLQs empty, outbox empty. Injected payment failures: 47 and 49, all retried (max 2 receives).

How to talk about the latency honestly: LocalStack (one Python process) ran at 122–144% CPU during
the burst while order-service spent ~600 ms server-side per request on average; the rest is
queueing behind Tomcat threads waiting on LocalStack. Run 1 was slower than run 2 and than two
earlier passing runs (p95 7.46 s, 7.37 s), and I did not pin down why. The test proves
correctness under concurrency, retries and injected faults; it is not a throughput benchmark.

Tests: 149 (111 unit, 38 integration/e2e), 0 failures, 0 skipped. Coverage 94.3% of lines
(987/1047) and 85.3% of branches, aggregated across unit, integration and e2e tests.

## 5. Likely interview questions (with answers)

**1. Why a saga instead of a distributed transaction?**
The data lives in Postgres, DynamoDB and an external payment processor. Two-phase commit across
them is not available (DynamoDB and card processors don't take part in XA) and would couple
availability: one slow participant blocks all. A saga splits the work into local transactions
with a compensating action for each step that can be undone (release stock when payment fails).
You accept a short window of inconsistency in exchange for availability and independent services.

**2. Choreography or orchestration, and when would you switch?**
Choreography here: three services, one compensation, and each service only needs to know the
events it consumes and produces. I'd switch to an orchestrator (Step Functions, Temporal) when
the flow grows (shipping, loyalty points, fraud checks), needs timeouts per step, or the business
wants a single view of where each order is. The trade-off is a central component that must be
highly available, in exchange for an explicit, testable workflow definition.

**3. What problem does the transactional outbox solve?**
The dual write. If you commit the order and then publish to SQS, a crash in between loses the
event and the order sits in `PENDING` forever. Publishing first and then committing can announce
an order that was rolled back. Writing the event into an outbox table in the same transaction
makes them atomic; a relay publishes later. The cost is at-least-once publishing, which
idempotent consumers absorb.

**4. How do you guarantee two concurrent requests with the same Idempotency-Key never create two orders?**
The first step is a DynamoDB conditional put (`attribute_not_exists(idempotencyKey)`), which is
atomic, so exactly one request wins the claim. The claim also stores a pre-generated order id,
and every request with that key uses it. That id is the Postgres primary key, so even if two
requests got as far as inserting (for example a stale-claim takeover), the second insert fails on
the PK and returns the existing order. The load test proves it: 1,000 keys, each sent twice, 0
duplicates; and an integration test fires 20 concurrent requests with one key: exactly one `201`.

**5. What happens if the order service crashes after claiming the key but before writing the order?**
The claim stays `IN_PROGRESS` with no order. A retry with the same key waits up to 3 s for the
order to appear; if it doesn't and the claim is older than 30 s, the retry takes it over and
creates the order with the originally assigned id. The PK guarantee still holds if two retries
race.

**6. How does inventory avoid double-reserving on duplicate delivery?**
One `TransactWriteItems` contains the marker put (`attribute_not_exists(orderId)`) and the stock
decrement (`stock >= :qty`). DynamoDB applies all or nothing. A duplicate fails the marker
condition, the transaction is cancelled (stock untouched), the service reads the stored marker
and re-publishes that outcome. Rejections are stored too, so a redelivery after a restock cannot
flip a rejected order into a reserved one.

**7. How do you make sure a customer is charged at most once?**
Three layers. A conditional put claims the order before the gateway call, so only one consumer can
charge. The order id is the gateway's idempotency key, so even a takeover after a crash mid-charge
gets the same result from the processor. And the stored result is written conditionally on still
owning the claim. Duplicates re-publish the stored result. Tests: 5 concurrent duplicate
deliveries → one charge, same transaction id in every event; in the load test payment rows equal
orders that reached payment.

**8. How do you handle PAYMENT_COMPLETED arriving before INVENTORY_RESERVED?**
The order stores `inventoryStatus` and `paymentStatus` separately. `PAYMENT_COMPLETED` first sets
payment to `COMPLETED` but keeps the order `PENDING`; `INVENTORY_RESERVED` then sees payment
already completed and moves straight to `CONFIRMED`. There are unit tests for both orders and an
integration test that sends them in reverse order over SQS.

**9. Why optimistic locking, and what happens on conflict?**
Two saga events for the same order regularly arrive within milliseconds on different listener
threads. Without a version check, the second write would overwrite the first (lost update). With
`@Version`, the second commit fails; the service reloads the order and reapplies the event (up to
5 attempts with jitter). If it still fails, the exception goes to SQS backoff. Pessimistic locks
would also work but hold connections longer under contention.

**10. Walk me through the retry policy.**
Spring Cloud AWS acknowledges a message only if the listener succeeds. On failure our error
handler reads `ApproximateReceiveCount`, sets the message visibility timeout to
`min(2 × 2^(n−1), 300)` seconds and rethrows, so SQS redelivers after 2, 4, 8, 16 s. On the sixth
receive the redrive policy (`maxReceiveCount = 5`) moves the message to the DLQ and an alarm fires.
The end-to-end test sets the gateway failure rate to 1.0 and measures exactly 5 attempts with
increasing gaps, then finds the message in `payment-queue-dlq`, then redrives it and sees the
order confirmed with one charge.

**11. Transient vs. permanent errors: how do you decide?**
Anything that can succeed later (timeouts, throttling, optimistic-lock conflicts, "order not found
yet" from replica lag) is transient and retried. Messages that can never succeed (unparseable
JSON, missing required fields, an event type that doesn't belong on the queue) throw a
`PermanentMessageException` and are copied to the DLQ immediately with the reason attached, then
acknowledged. Unknown exceptions default to transient: retrying something permanent costs a few
seconds, while dropping something transient loses an order.

**12. Why DynamoDB for inventory but Postgres for orders?**
Inventory and payments are key-value, write-heavy and contention-prone ("decrement if enough"),
which DynamoDB conditional writes do atomically without locks or connection pools. Orders have
items, need flexible queries (support, reporting, the reconciliation script) and must commit
atomically with the outbox, which is what a relational database is good at. In AWS: RDS
PostgreSQL plus DynamoDB on-demand.

**13. How would you know something is wrong in production?**
CloudWatch alarms to SNS: any DLQ non-empty; oldest message older than 5 minutes on any work
queue (consumers down or behind); order failure rate above 20% over 5 minutes; consumer failure
spikes per queue; outbox backlog. Each has a runbook entry: what it means, how to inspect
queues, DLQ messages and order state, how to redrive safely, when to escalate. Metrics come from
Micrometer: `orders.created`, `orders.transitions{status}`, `orders.saga.duration`,
`consumer.failures{queue,kind}`, `consumer.processing.latency{queue}`, `outbox.pending`.

**14. How do you fix an order stuck in PENDING?**
Look at what each service decided (`/inventory/reservations/{id}`, `/payments/{id}`) and check the
DLQs. After fixing the root cause, redrive the DLQ or call `POST /admin/orders/{id}/resume`, which
re-emits `ORDER_CREATED`. Because every step replays its stored outcome, that continues the saga
from wherever it stopped without double-reserving or double-charging. Never patch the status by
hand: the other services would disagree with it.

**15. What did the load test prove, and what are its limits?**
1,000 concurrent orders, each retried immediately with the same key, with 5% injected payment
failures: all requests succeeded, every retry returned the original order, no key produced two
orders, every order reached a terminal state, stock decreased by exactly the confirmed quantity,
payments matched, and all DLQs were empty, over repeated runs. Limits: everything runs on one
laptop, and LocalStack (a single Python process) is the bottleneck, so latency figures are
pessimistic compared with real SQS and DynamoDB. The test is about correctness under concurrency
and duplication, not peak throughput. Next steps would be a soak test, multiple order-service
instances (the outbox relay already supports that with `SKIP LOCKED`), and a run against real AWS.

**Bonus: what would you change for production?**
Authentication on the API and admin endpoint; a real payment adapter; per-message tracing
(OpenTelemetry, correlation ids in message attributes); CDC (Debezium) instead of polling if the
outbox volume grows; DynamoDB write sharding for very hot SKUs; outbox row cleanup; contract tests
for the event schema; ECS auto-scaling on queue depth.
