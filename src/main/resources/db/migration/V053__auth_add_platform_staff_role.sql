-- =============================================================================
-- V053 — PLATFORM_STAFF role for the internal console
--
-- PRD v2.1 §7.8.3 and §7.1.3 require promotional-credit grants to be
-- "staff-only and fully audited": restricted to the internal console role, with
-- a mandatory reason and an AuditLog row, and with "no employer-facing path"
-- able to create a PROMO_CREDIT transaction.
--
-- The existing roles are all employer-facing — ADMIN is a company's own
-- administrator, not ours. Reusing it would put the ability to mint free credit
-- in the hands of every customer's admin user, which is the precise thing the
-- PRD forbids. Hence a distinct role that no self-service registration path can
-- assign.
--
-- The internal console also carries manual wallet credit and the cost-tracking
-- views (§7.8.2, §11), so this role gates that whole surface, not only grants.
-- =============================================================================

ALTER TABLE users
    DROP CONSTRAINT IF EXISTS ck_users_role_valid;

ALTER TABLE users
    ADD CONSTRAINT ck_users_role_valid
        CHECK (role IN ('ADMIN', 'RECRUITER', 'VIEWER', 'PLATFORM_STAFF'));
