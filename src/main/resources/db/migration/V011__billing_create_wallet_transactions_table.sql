-- =============================================================================
-- V011__billing_create_wallet_transactions_table.sql
-- depends on: V001__company_create_companies_table.sql
--             V007__session_create_interview_sessions_table.sql
--             V010__billing_create_wallets_table.sql
-- =============================================================================

CREATE TABLE wallet_transactions (
    id                  UUID        NOT NULL DEFAULT gen_random_uuid(),
    company_id          UUID        NOT NULL,
    wallet_id           UUID        NOT NULL,
    session_id          UUID,                    -- nullable: not all transactions are session-linked
    transaction_type    VARCHAR(50) NOT NULL,
    amount_paise        BIGINT      NOT NULL,
    balance_after_paise BIGINT      NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_wallet_transactions
        PRIMARY KEY (id),

    -- composite FK: guarantees the referenced wallet belongs to the same company
    -- as the transaction — closes the cross-tenant boundary on the billing side.
    -- requires uq_wallets_company_id_id on wallets(company_id, id) — added in V010
    CONSTRAINT fk_wallet_transactions_wallets
        FOREIGN KEY (company_id, wallet_id) REFERENCES wallets (company_id, id) ON DELETE RESTRICT,

    -- composite FK: guarantees the referenced session belongs to the same company.
    -- ON DELETE SET NULL (session_id): PostgreSQL 15 column-scoped SET NULL —
    -- nulls only session_id when the session is deleted; company_id (NOT NULL)
    -- is intentionally excluded. Without the column list, SET NULL would attempt
    -- to null company_id too and fail the NOT NULL constraint on every session delete.
    -- requires uq_interview_sessions_company_id_id on interview_sessions(company_id, id) — added in V007
    CONSTRAINT fk_wallet_transactions_sessions
        FOREIGN KEY (company_id, session_id) REFERENCES interview_sessions (company_id, id)
        ON DELETE SET NULL (session_id),

    -- amount is always stored as a positive magnitude;
    -- transaction_type carries the sign semantics (DEBIT vs CREDIT)
    CONSTRAINT ck_wallet_transactions_amount_positive
        CHECK (amount_paise > 0),

    -- balance snapshot after this transaction must never be negative
    CONSTRAINT ck_wallet_transactions_balance_after_non_negative
        CHECK (balance_after_paise >= 0),

    CONSTRAINT ck_wallet_transactions_type_valid
        CHECK (transaction_type IN ('CREDIT', 'DEBIT', 'RESERVE', 'RELEASE'))
);
