-- =============================================================================
-- V026__billing_alter_wallet_transactions.sql
-- purpose: align wallet_transactions with the authoritative design in the
--          migration plan (Section 5.2–5.3) by:
--
--   1. UPDATE the transaction_type CHECK constraint enum to plan-aligned values
--      V011 defined: CREDIT, DEBIT, RESERVE, RELEASE
--      Plan defines:  TOPUP, RESERVATION, SETTLEMENT, RELEASE, REFUND
--      Column name is retained as transaction_type (NOT renamed to 'type').
--      Rationale: 'type' is a Hibernate 6 reserved discriminator keyword;
--      mapping @Column(name = "type") causes ambiguity in inheritance contexts
--      and validator mismatches at startup. transaction_type is unambiguous.
--
--   2. ADD status VARCHAR(50) NOT NULL DEFAULT 'CONFIRMED'
--      Tracks the lifecycle of each transaction record. Required for
--      RESERVATION transactions that exist in PENDING state until
--      session outcome is known (SETTLEMENT or RELEASE).
--      Existing rows default to CONFIRMED — they represent completed
--      transactions created before the status lifecycle was introduced.
--
--   3. ADD razorpay_order_id VARCHAR(255) + UNIQUE constraint
--      Stores the Razorpay order ID on TOPUP transactions, enabling the
--      Razorpay webhook handler to correlate a payment.captured event to
--      the specific wallet transaction that initiated the top-up.
--      UNIQUE constraint (H7): prevents double-crediting if a bug causes
--      WalletService.confirmTopUp() to fire twice for the same payment.
--      PostgreSQL UNIQUE allows multiple NULLs — non-TOPUP rows unaffected.
--
--   4. idx_wallet_txn_razorpay_order: DROPPED in favour of the UNIQUE index.
--      The UNIQUE constraint on razorpay_order_id creates a B-tree index that
--      serves the same webhook correlation lookup. A second separate index
--      would be redundant write overhead.
--
--   5. idx_wallet_txn_pending: deferred from V025 — requires status column.
-- =============================================================================


-- =============================================================================
-- STEP 1: UPDATE transaction_type ENUM TO PLAN-ALIGNED VALUES
-- =============================================================================

-- Drop the existing CHECK constraint (references old enum values)
ALTER TABLE wallet_transactions
    DROP CONSTRAINT ck_wallet_transactions_type_valid;

-- Re-add with plan-aligned enum aligned to Section 5.3 atomic operations:
--   TOPUP       — funds credited to wallet from a confirmed Razorpay payment
--   RESERVATION — funds ring-fenced for a session (reserved_paise increases)
--   SETTLEMENT  — session charge finalised (balance down, reserved down)
--   RELEASE     — reservation returned (reserved_paise decreases)
--   REFUND      — manual credit adjustment by platform admin
ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_type_valid
        CHECK (transaction_type IN ('TOPUP', 'RESERVATION', 'SETTLEMENT', 'RELEASE', 'REFUND'));


-- =============================================================================
-- STEP 2: ADD status COLUMN
-- =============================================================================

-- DEFAULT 'CONFIRMED': existing rows represent completed transactions;
-- this is the correct retroactive semantic assignment.
ALTER TABLE wallet_transactions
    ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'CONFIRMED';

ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_status_valid
        CHECK (status IN ('PENDING', 'CONFIRMED', 'RELEASED', 'FAILED'));


-- =============================================================================
-- STEP 3: ADD razorpay_order_id COLUMN
-- =============================================================================

-- Nullable: only TOPUP transactions carry a Razorpay order ID.
ALTER TABLE wallet_transactions
    ADD COLUMN razorpay_order_id VARCHAR(255);

-- Razorpay order ID must be a meaningful string when present
ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_razorpay_order_not_empty
        CHECK (razorpay_order_id IS NULL OR length(trim(razorpay_order_id)) > 0);

-- Only TOPUP transactions should carry a razorpay_order_id
ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_razorpay_order_topup_only
        CHECK (razorpay_order_id IS NULL OR transaction_type = 'TOPUP');

-- UNIQUE constraint: prevents double-crediting if confirmTopUp() fires twice
-- for the same Razorpay payment. PostgreSQL UNIQUE allows multiple NULLs,
-- so RESERVATION, SETTLEMENT, RELEASE, and REFUND rows are unaffected.
-- This constraint also creates the B-tree index used for webhook correlation
-- lookups — no separate idx_wallet_txn_razorpay_order is needed.
ALTER TABLE wallet_transactions
    ADD CONSTRAINT uq_wallet_transactions_razorpay_order_id
        UNIQUE (razorpay_order_id);


-- =============================================================================
-- STEP 4: DEFERRED INDEX FROM V025
-- =============================================================================

-- Active reservation lookup: find PENDING reservations for a wallet.
-- Used by WalletService to locate the reservation record before settlement/release.
-- partial: only PENDING rows (a small fraction) are relevant.
--   WHERE wallet_id = ? AND transaction_type = 'RESERVATION' AND status = 'PENDING'
CREATE INDEX idx_wallet_txn_pending
    ON wallet_transactions (wallet_id, transaction_type)
    WHERE status = 'PENDING';
