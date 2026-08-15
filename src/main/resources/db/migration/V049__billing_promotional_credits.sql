-- =============================================================================
-- V049 — Promotional credits
--
-- PRD v2.1 §7.8.3. Called "the single highest-leverage item in the whole change
-- set for hitting 20 paying clients in 60 days" — nobody buys an AI interviewer
-- they have not watched run. New companies get roughly three free interviews
-- (Rs.300) on email verification, one per company, behind abuse guards.
--
-- WHY A DISTINCT BALANCE AND A DISTINCT TRANSACTION TYPE, rather than just
-- crediting the wallet:
--
--   * GST invoices cover paid top-ups only, because promotional credit is not a
--     sale. If free credit appeared on an invoice, the accounting and the tax
--     filing would disagree with each other. A separate balance is what makes
--     "invoice the paid movement only" mechanically checkable.
--
--   * Free credit must stay out of revenue reporting.
--
--   * The dashboard must show the split — "Balance Rs.700 (Rs.200 promotional,
--     expires 30 Sep)". A customer must never be surprised about which money is
--     being spent.
--
-- SPEND ORDER IS PROMOTIONAL FIRST, PAID SECOND. Never the reverse. A customer
-- must never see paid money consumed while free credit sits unused — that is a
-- refund request and a trust problem. The ordering applies to reservations as
-- well as settlements, and is implemented in WalletService; this migration
-- provides the two balances it moves between.
--
-- Promotional grants are staff-only and fully audited (§7.1.3). There is no
-- employer-facing path that can create a PROMO_CREDIT transaction, which is why
-- granted_by_staff_id and grant_reason are recorded on the transaction itself
-- rather than left to the audit log alone.
-- =============================================================================

-- ── Wallet: split the balance ───────────────────────────────────────────────
-- balance_paise keeps its meaning as the PAID balance; the promotional balance
-- is tracked alongside it. Reserved funds are not split: a reservation is a
-- claim on the combined balance, and which pot settles it is decided at
-- settlement time by the spend ordering.
ALTER TABLE wallets
    ADD COLUMN promo_balance_paise BIGINT NOT NULL DEFAULT 0;

ALTER TABLE wallets
    ADD CONSTRAINT ck_wallets_promo_balance_non_negative
        CHECK (promo_balance_paise >= 0);

-- V010's ck_wallets_balance_gte_reserved compared reserved against the paid
-- balance alone. With two pots, a reservation may legitimately be covered by
-- promotional credit, so the invariant is against the combined balance.
ALTER TABLE wallets
    DROP CONSTRAINT IF EXISTS ck_wallets_balance_gte_reserved;

ALTER TABLE wallets
    ADD CONSTRAINT ck_wallets_total_balance_gte_reserved
        CHECK (balance_paise + promo_balance_paise >= reserved_paise);

-- V022's trigger enforces the same invariant at row level and must agree with
-- the constraint above, or a legitimate promo-funded reservation raises
-- wallet_integrity_violation.
CREATE OR REPLACE FUNCTION fn_check_wallet_balance()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.reserved_paise < 0 THEN
        RAISE EXCEPTION
            'wallet_integrity_violation: reserved_paise (%) cannot be negative on wallet id=%',
            NEW.reserved_paise, NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.balance_paise < 0 THEN
        RAISE EXCEPTION
            'wallet_integrity_violation: balance_paise (%) cannot be negative on wallet id=%',
            NEW.balance_paise, NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.promo_balance_paise < 0 THEN
        RAISE EXCEPTION
            'wallet_integrity_violation: promo_balance_paise (%) cannot be negative on wallet id=%',
            NEW.promo_balance_paise, NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    -- A reservation may be covered by either pot, so the check is against the
    -- combined balance (see V049 header on spend ordering).
    IF (NEW.balance_paise + NEW.promo_balance_paise) < NEW.reserved_paise THEN
        RAISE EXCEPTION
            'wallet_integrity_violation: combined balance (paid % + promo %) cannot be less than reserved_paise (%) on wallet id=%',
            NEW.balance_paise, NEW.promo_balance_paise, NEW.reserved_paise, NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

-- ── Transactions: the PROMO_CREDIT type and its grant metadata ──────────────
ALTER TABLE wallet_transactions
    DROP CONSTRAINT ck_wallet_transactions_type_valid;

ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_type_valid
        CHECK (transaction_type IN (
            'TOPUP',          -- paid, invoiced, GST-bearing
            'PROMO_CREDIT',   -- free, never invoiced
            'PROMO_EXPIRY',   -- reversing entry when unspent promo credit lapses
            'RESERVATION',
            'SETTLEMENT',
            'RELEASE',
            'REFUND'
        ));

ALTER TABLE wallet_transactions
    ADD COLUMN is_promotional      BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN expires_at          TIMESTAMPTZ,
    ADD COLUMN grant_reason        VARCHAR(500),
    ADD COLUMN granted_by_staff_id UUID,
    ADD COLUMN import_batch_id     UUID,
    ADD COLUMN gst_paise           BIGINT;

ALTER TABLE wallet_transactions
    ADD CONSTRAINT fk_wallet_transactions_granted_by_staff
        FOREIGN KEY (granted_by_staff_id) REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE wallet_transactions
    ADD CONSTRAINT fk_wallet_transactions_import_batches
        FOREIGN KEY (company_id, import_batch_id)
            REFERENCES candidate_import_batches (company_id, id) ON DELETE SET NULL;

-- The grant reason is mandatory on a grant, and meaningless anywhere else.
-- "with a mandatory reason" appears three times in the PRD for this endpoint.
ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_promo_grant_requires_reason
        CHECK (
            transaction_type <> 'PROMO_CREDIT'
            OR (grant_reason IS NOT NULL AND length(trim(grant_reason)) > 0)
        );

-- Only a promotional grant may carry an expiry, and only a promotional
-- transaction may be flagged promotional.
ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_expiry_promo_only
        CHECK (expires_at IS NULL OR transaction_type = 'PROMO_CREDIT');

ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_is_promotional_consistency
        CHECK (
            (transaction_type IN ('PROMO_CREDIT', 'PROMO_EXPIRY') AND is_promotional)
            OR (transaction_type NOT IN ('PROMO_CREDIT', 'PROMO_EXPIRY'))
        );

-- Promotional credit is not a sale, so it never bears GST (§7.8.3, §8 Tax).
ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_no_gst_on_promotional
        CHECK (NOT is_promotional OR gst_paise IS NULL OR gst_paise = 0);

ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_gst_non_negative
        CHECK (gst_paise IS NULL OR gst_paise >= 0);

-- The expiry sweep claims grants that have lapsed and writes a reversing entry.
CREATE INDEX idx_wallet_transactions_promo_expiry
    ON wallet_transactions (expires_at)
    WHERE transaction_type = 'PROMO_CREDIT' AND expires_at IS NOT NULL;

-- Invoice generation reads paid top-ups only.
CREATE INDEX idx_wallet_transactions_paid_topups
    ON wallet_transactions (company_id, created_at DESC)
    WHERE transaction_type = 'TOPUP';

-- ── Company: one grant per company ──────────────────────────────────────────
-- The self-serve signup grant is applied on email verification and is one per
-- company. This timestamp is the record of that, and is what the grant path
-- checks before creating a PROMO_CREDIT — the abuse guards on email domain and
-- payment instrument (§7.8.3) sit in front of it, not instead of it.
ALTER TABLE companies
    ADD COLUMN promo_grant_applied_at TIMESTAMPTZ;
