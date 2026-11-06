-- Flash sales sold through Redis (docs/shop-design.md §16). Who may buy is decided in Redis; MySQL keeps the sale,
-- one order per buyer as the last line of defence, and the units still to be given back to Redis.
CREATE TABLE flash_sale (
    sale_id    VARCHAR(36) NOT NULL PRIMARY KEY,
    sku        VARCHAR(32) NOT NULL,
    stock      INT         NOT NULL,
    starts_at  DATETIME(3) NOT NULL,
    ends_at    DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT flash_sale_stock_positive CHECK (stock > 0),
    CONSTRAINT flash_sale_product FOREIGN KEY (sku) REFERENCES product (sku)
);

-- The sale an order was placed through; NULL for regular orders, and MySQL never treats NULLs as duplicates.
ALTER TABLE orders
    ADD COLUMN flash_sale_id VARCHAR(36) NULL,
    ADD CONSTRAINT orders_flash_sale_buyer UNIQUE (flash_sale_id, user_id);

-- Outbox: a cancelled flash sale order gives its unit back to the sale in Redis. Written in the same transaction as
-- the cancellation and deleted once Redis has the unit back, so a crash in between only delays the return.
CREATE TABLE flash_sale_return (
    order_id        VARCHAR(36) NOT NULL PRIMARY KEY,
    sale_id         VARCHAR(36) NOT NULL,
    user_id         VARCHAR(64) NOT NULL,
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    INDEX flash_sale_return_due (next_attempt_at),
    CONSTRAINT flash_sale_return_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
);
