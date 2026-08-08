ALTER TABLE wallet_transactions
    ADD COLUMN razorpay_payment_id VARCHAR(255),
    ADD COLUMN description         VARCHAR(500);

ALTER TABLE wallet_transactions
    ADD CONSTRAINT uq_wallet_transactions_razorpay_payment_id
        UNIQUE (razorpay_payment_id);

ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_payment_id_topup_only
        CHECK (razorpay_payment_id IS NULL OR transaction_type = 'TOPUP');

ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_payment_requires_order
        CHECK (
            razorpay_payment_id IS NULL
            OR razorpay_order_id IS NOT NULL
        );

ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_payment_id_not_empty
        CHECK (razorpay_payment_id IS NULL OR length(trim(razorpay_payment_id)) > 0);

ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_description_not_empty
        CHECK (description IS NULL OR length(trim(description)) > 0);
