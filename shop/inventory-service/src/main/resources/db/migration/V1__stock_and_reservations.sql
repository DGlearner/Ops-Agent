-- Stock ledger. The CHECK constraints are the last line of defence against overselling or losing units: any update
-- that would make a count negative or unbalance the ledger fails.
CREATE TABLE stock (
    sku        VARCHAR(32) NOT NULL PRIMARY KEY,
    total      INT         NOT NULL,
    available  INT         NOT NULL,
    reserved   INT         NOT NULL DEFAULT 0,
    sold       INT         NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT stock_non_negative CHECK (available >= 0 AND reserved >= 0 AND sold >= 0),
    CONSTRAINT stock_balanced CHECK (available + reserved + sold = total)
);

-- One row per order. A RELEASED row without items is a tombstone written by a release that arrived before its
-- reservation; it makes the late reservation fail instead of holding stock forever.
CREATE TABLE reservation (
    order_id   VARCHAR(36) NOT NULL PRIMARY KEY,
    status     VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL
);

CREATE TABLE reservation_item (
    order_id VARCHAR(36) NOT NULL,
    sku      VARCHAR(32) NOT NULL,
    quantity INT         NOT NULL,
    PRIMARY KEY (order_id, sku),
    CONSTRAINT reservation_item_order FOREIGN KEY (order_id) REFERENCES reservation (order_id),
    CONSTRAINT reservation_item_sku FOREIGN KEY (sku) REFERENCES stock (sku)
);
