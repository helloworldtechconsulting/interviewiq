-- =============================================================================
-- V010__billing_create_wallets_table.sql
-- depends on: V001__company_create_companies_table.sql
-- =============================================================================

CREATE TABLE wallets (
    id             UUID   NOT NULL DEFAULT gen_random_uuid(),
    company_id     UUID   NOT NULL,
    balance_paise  BIGINT NOT NULL DEFAULT 0,
    reserved_paise BIGINT NOT NULL DEFAULT 0,

    -- optimistic locking: incremented on every UPDATE by the application layer;
    -- prevents lost updates under concurrent billing operations.
    -- mapped to @Version in the JPA Wallet entity.
    version        BIGINT NOT NULL DEFAULT 0,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_wallets
        PRIMARY KEY (id),

    -- one wallet per company — enforced at the DB level
    CONSTRAINT uq_wallets_company
        UNIQUE (company_id),

    -- enables composite FK from child tables (e.g. wallet_transactions) to enforce
    -- that a referenced wallet belongs to the same company as the child row
    CONSTRAINT uq_wallets_company_id_id
        UNIQUE (company_id, id),

    CONSTRAINT fk_wallets_companies
        FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE RESTRICT,

    -- monetary invariants — all three must hold simultaneously
    CONSTRAINT ck_wallets_balance_non_negative
        CHECK (balance_paise >= 0),

    CONSTRAINT ck_wallets_reserved_non_negative
        CHECK (reserved_paise >= 0),

    -- available funds can never drop below what is already reserved.
    -- derived value: available_paise = balance_paise - reserved_paise >= 0
    CONSTRAINT ck_wallets_balance_gte_reserved
        CHECK (balance_paise >= reserved_paise),

    -- guards against accidental version corruption via manual SQL or buggy migrations;
    -- optimistic locking breaks silently if version wraps below zero
    CONSTRAINT ck_wallets_version_non_negative
        CHECK (version >= 0)
);
