-- =============================================================================
-- V031__billing_add_wallet_transaction_audit_columns.sql
-- purpose: add Razorpay payment ID and human-readable description to
--          wallet_transactions for full payment-lifecycle auditability.
--
-- COLUMNS ADDED:
--
--   razorpay_payment_id — Razorpay-assigned payment ID returned in the
--                         payment.captured webhook (e.g. "pay_NxYz1234567890").
--                         DISTINCT from razorpay_order_id (added in V026):
--                           razorpay_order_id  — created upfront by the backend
--                                                when the user initiates top-up
--                           razorpay_payment_id — assigned by Razorpay after the
--                                                  user completes payment; arrives
--                                                  in the webhook body
--                         UNIQUENESS: used as an idempotency key on the webhook
--                         handler so that a redelivered payment.captured event
--                         cannot double-credit the wallet.
--
--   description        — human-readable ledger narrative for the admin panel and
--                         future billing exports. Examples:
--                           "Wallet top-up — ₹500 via Razorpay order ord_Abc123"
--                           "Interview session deducted — candidate Jane Doe"
--                           "Session refund — interview cancelled before start"
--
-- WHY BOTH IDs?
--   Razorpay's reconciliation API (order-level) uses order_id.
--   Razorpay's refund API requires payment_id as the reference.
--   Storing both eliminates an API round-trip when issuing a refund.
-- =============================================================================


ALTER TABLE wallet_transactions
    ADD COLUMN razorpay_payment_id VARCHAR(255),
    ADD COLUMN description         VARCHAR(500);


-- ── razorpay_payment_id constraints ──────────────────────────────────────────

-- Idempotency: each Razorpay payment ID must appear at most once in the ledger.
-- PostgreSQL UNIQUE permits multiple NULLs — non-TOPUP rows are unaffected.
ALTER TABLE wallet_transactions
    ADD CONSTRAINT uq_wallet_transactions_razorpay_payment_id
        UNIQUE (razorpay_payment_id);

-- Only TOPUP transactions are linked to a Razorpay payment
ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_payment_id_topup_only
        CHECK (razorpay_payment_id IS NULL OR transaction_type = 'TOPUP');

-- Both IDs must travel together: a payment_id without an order_id indicates
-- a data entry error in the webhook handler
ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_payment_requires_order
        CHECK (
            razorpay_payment_id IS NULL
            OR razorpay_order_id IS NOT NULL
        );

-- payment ID must be a meaningful string when present
ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_payment_id_not_empty
        CHECK (razorpay_payment_id IS NULL OR length(trim(razorpay_payment_id)) > 0);


-- ── description constraints ───────────────────────────────────────────────────

-- description must be a meaningful string when present
ALTER TABLE wallet_transactions
    ADD CONSTRAINT ck_wallet_transactions_description_not_empty
        CHECK (description IS NULL OR length(trim(description)) > 0);
