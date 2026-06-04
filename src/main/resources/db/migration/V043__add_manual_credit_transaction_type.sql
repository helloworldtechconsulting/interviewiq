ALTER TABLE wallet_transactions
DROP CONSTRAINT ck_wallet_transactions_type_valid;

ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_type_valid
        CHECK (
            transaction_type IN (
                                 'TOPUP',
                                 'MANUAL_CREDIT',
                                 'RESERVATION',
                                 'SETTLEMENT',
                                 'RELEASE',
                                 'REFUND'
                )
            );