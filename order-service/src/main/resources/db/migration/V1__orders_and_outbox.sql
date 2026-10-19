CREATE TABLE orders (
    id               UUID PRIMARY KEY,
    -- Not unique on purpose: exactly-once creation per key is enforced by the DynamoDB
    -- idempotency_keys table, which fixes the order id for a key before the insert.
    -- The primary key above is the database-level backstop.
    idempotency_key  VARCHAR(255)   NOT NULL,
    customer_id      VARCHAR(100)   NOT NULL,
    total_amount     NUMERIC(19, 2) NOT NULL,
    status           VARCHAR(32)    NOT NULL,
    inventory_status VARCHAR(32)    NOT NULL,
    payment_status   VARCHAR(32)    NOT NULL,
    failure_reason   VARCHAR(500),
    created_at       TIMESTAMPTZ    NOT NULL,
    updated_at       TIMESTAMPTZ    NOT NULL,
    version          BIGINT         NOT NULL
);

CREATE INDEX idx_orders_status ON orders (status);
CREATE INDEX idx_orders_idempotency_key ON orders (idempotency_key);

CREATE TABLE order_items (
    order_id   UUID           NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    line_no    INT            NOT NULL,
    sku        VARCHAR(64)    NOT NULL,
    quantity   INT            NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(19, 2) NOT NULL CHECK (unit_price >= 0),
    PRIMARY KEY (order_id, line_no)
);

-- Transactional outbox: rows are written in the same transaction as the order change and
-- published to SQS by OutboxPublisher.
CREATE TABLE outbox_events (
    id           UUID PRIMARY KEY,
    aggregate_id UUID         NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    destination  VARCHAR(128) NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
