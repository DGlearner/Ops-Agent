CREATE TABLE product (
    sku         VARCHAR(32)  NOT NULL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    price_cents BIGINT       NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT product_price_positive CHECK (price_cents > 0)
);

-- (user_id, idempotency_key) is unique, so a retried request can never create a second order.
CREATE TABLE orders (
    order_id        VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id         VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash    CHAR(64)    NOT NULL,
    status          VARCHAR(20) NOT NULL,
    failure_reason  VARCHAR(32),
    amount_cents    BIGINT      NOT NULL,
    currency        CHAR(3)     NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    updated_at      DATETIME(3) NOT NULL,
    CONSTRAINT orders_idempotency UNIQUE (user_id, idempotency_key),
    INDEX orders_status_updated (status, updated_at)
);

CREATE TABLE order_item (
    order_id         VARCHAR(36) NOT NULL,
    sku              VARCHAR(32) NOT NULL,
    quantity         INT         NOT NULL,
    unit_price_cents BIGINT      NOT NULL,
    PRIMARY KEY (order_id, sku),
    CONSTRAINT order_item_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
);

-- Outbox for the inventory command that settles an order (confirm when paid, release when cancelled). It is written
-- in the same transaction as the order's final status, and retried until inventory acknowledges it.
CREATE TABLE inventory_task (
    order_id        VARCHAR(36)  NOT NULL PRIMARY KEY,
    action          VARCHAR(10)  NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3)  NOT NULL,
    last_error      VARCHAR(255),
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    INDEX inventory_task_due (status, next_attempt_at),
    CONSTRAINT inventory_task_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
);
