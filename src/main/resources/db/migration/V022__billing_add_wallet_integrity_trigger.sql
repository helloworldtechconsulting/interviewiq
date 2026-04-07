-- =============================================================================
-- V022__billing_add_wallet_integrity_trigger.sql
-- purpose: BEFORE UPDATE trigger that enforces the wallet integrity invariant
--          balance_paise >= reserved_paise >= 0 with a structured, actionable
--          exception message for incident debugging.
--
-- DEFENCE LAYER CONTEXT (migration plan Section 5.2 Layer 3):
--   Layer 1 — CHECK constraints (V010): ck_wallets_balance_non_negative,
--             ck_wallets_reserved_non_negative, ck_wallets_balance_gte_reserved
--             enforce the invariant at the storage engine level.
--   Layer 2 — Optimistic lock version column (V010): prevents lost updates
--             under concurrent billing operations via @Version in JPA.
--   Layer 3 — THIS TRIGGER: generates named, structured exception messages
--             that include the violating values and wallet ID, making incident
--             debugging significantly faster than a bare constraint violation.
--
-- WHY REDUNDANT WITH CHECK CONSTRAINTS?
--   (a) Precise error messages: CHECK says "constraint violated"; the trigger
--       says "balance_paise 200 < reserved_paise 250 on wallet id=..." —
--       critical for fast incident triage in production.
--
-- IMPORTANT — session_replication_role = replica:
--   Setting this mode bypasses BOTH non-deferrable CHECK constraints AND
--   non-replica triggers. Neither this trigger nor the V010 CHECK constraints
--   provide protection in replica mode. The CHECK constraints are the
--   mechanical integrity guarantee in normal operation; this trigger is the
--   diagnostic layer only — not an additional security boundary.
-- =============================================================================


-- fn_check_wallet_balance() is CREATE OR REPLACE for idempotency — safe to
-- re-run in test environments after partial failure.
CREATE OR REPLACE FUNCTION fn_check_wallet_balance()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    -- Check reserved_paise >= 0 first (simplest invariant)
    IF NEW.reserved_paise < 0 THEN
        RAISE EXCEPTION
            'wallet_integrity_violation: reserved_paise (%) cannot be negative on wallet id=%',
            NEW.reserved_paise, NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    -- Check balance_paise >= 0
    IF NEW.balance_paise < 0 THEN
        RAISE EXCEPTION
            'wallet_integrity_violation: balance_paise (%) cannot be negative on wallet id=%',
            NEW.balance_paise, NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    -- The critical invariant: total funds must always cover the reserved amount.
    -- Violated when a deduction reduces balance_paise below reserved_paise
    -- (e.g. a buggy settlement that decrements balance without also releasing
    -- the reservation, or two concurrent deductions racing past the reservation).
    IF NEW.balance_paise < NEW.reserved_paise THEN
        RAISE EXCEPTION
            'wallet_integrity_violation: balance_paise (%) cannot be less than reserved_paise (%) on wallet id=%',
            NEW.balance_paise, NEW.reserved_paise, NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;


-- BEFORE UPDATE only — INSERT is already guarded by the CHECK constraints
-- in V010. Restricting the trigger to UPDATE avoids doubling enforcement
-- overhead on the low-frequency wallet creation path.
CREATE TRIGGER trg_wallets_before_update_check_balance
    BEFORE UPDATE ON wallets
    FOR EACH ROW
EXECUTE FUNCTION fn_check_wallet_balance();
