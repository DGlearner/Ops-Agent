-- A VOIDED row is a placeholder written by reconciliation when an order's charge never arrived. It carries no
-- payment details; a charge request arriving later for the same order finds it and is refused.
ALTER TABLE payment
    MODIFY user_id VARCHAR(64) NULL,
    MODIFY amount_cents BIGINT NULL,
    MODIFY currency CHAR(3) NULL,
    MODIFY payment_token VARCHAR(32) NULL,
    DROP CHECK payment_amount_positive,
    ADD CONSTRAINT payment_details CHECK (status = 'VOIDED' OR (user_id IS NOT NULL AND amount_cents > 0
        AND currency IS NOT NULL AND payment_token IS NOT NULL)),
    ADD INDEX payment_status_created (status, created_at);
