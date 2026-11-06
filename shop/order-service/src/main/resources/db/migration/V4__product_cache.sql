-- Product details are served through a cache (docs/shop-design.md §17). Every change bumps the version, so a cache
-- write can tell an old copy from a new one; MySQL stays the source of truth, and orders are always priced from it.
ALTER TABLE product
    ADD COLUMN description VARCHAR(512) NOT NULL DEFAULT '',
    ADD COLUMN version     BIGINT       NOT NULL DEFAULT 1,
    ADD COLUMN updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

-- Outbox: the cache entry to invalidate after a product changed. Written in the transaction that changes the product,
-- so a crash or a Redis outage right after the commit cannot leave the old copy cached until it expires. One row per
-- SKU, holding the newest version to invalidate.
CREATE TABLE product_cache_invalidation (
    sku             VARCHAR(32) NOT NULL PRIMARY KEY,
    version         BIGINT      NOT NULL,
    attempts        INT         NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    INDEX product_cache_invalidation_due (next_attempt_at),
    CONSTRAINT product_cache_invalidation_product FOREIGN KEY (sku) REFERENCES product (sku)
);
