-- At most one payment per order. A row is written as PROCESSING before the (simulated) provider is called, so a
-- concurrent duplicate finds it and never charges a second time.
CREATE TABLE payment (
    payment_id     VARCHAR(36) NOT NULL PRIMARY KEY,
    order_id       VARCHAR(36) NOT NULL,
    user_id        VARCHAR(64) NOT NULL,
    amount_cents   BIGINT      NOT NULL,
    currency       CHAR(3)     NOT NULL,
    -- A test token such as tok_ok, never card data; kept so a stuck PROCESSING payment can be resolved later.
    payment_token  VARCHAR(32) NOT NULL,
    status         VARCHAR(16) NOT NULL,
    decline_reason VARCHAR(32),
    created_at     DATETIME(3) NOT NULL,
    updated_at     DATETIME(3) NOT NULL,
    CONSTRAINT payment_order UNIQUE (order_id),
    CONSTRAINT payment_amount_positive CHECK (amount_cents > 0)
);
