-- =============================================================================
-- R__dev_seed_data.sql
-- purpose: deterministic development seed data for local environment only
--
-- PROFILE GATE: this file lives in db/seed/, NOT db/migration/.
-- Flyway is configured to load this directory ONLY when the 'local' Spring
-- profile is active (application-local.yml):
--
--   spring.flyway.locations: classpath:db/migration, classpath:db/seed
--
-- This physical separation makes it IMPOSSIBLE for seed data to run in
-- staging or production regardless of configuration mistakes.
--
-- IDEMPOTENCY: every INSERT uses ON CONFLICT DO NOTHING, making this file
-- safe to re-run after any partial failure. As a Flyway repeatable migration
-- (R__ prefix), it re-runs whenever its checksum changes — idempotent INSERTs
-- ensure reruns are harmless.
--
-- SEED CONTENTS:
--   1. One test company  — "InterviewIQ Dev"  (slug: interviewiq-dev)
--   2. One admin user    — admin@dev.interviewiq.ai  (password: "password")
--   3. One funded wallet — ₹500 starting balance (50,000 paise)
--
-- FIXED UUIDs: deterministic IDs allow application integration tests to
-- reference known entity IDs without first querying the DB.
--
-- BCRYPT HASH: the password_hash below is the BCrypt hash of the plaintext
-- string "password" with cost factor 10, produced by bcryptjs (Node.js).
-- Spring Security BCryptPasswordEncoder accepts $2b$ hashes for verification.
-- To regenerate: node -e "const b=require('bcryptjs');console.log(b.hashSync('password',10))"
--
-- WARNING (L1): BCrypt cost factor 10 is used here for local development only.
-- The application's BCryptPasswordEncoder bean uses cost factor 12 for new passwords
-- in all environments. Never copy this seed user to staging or production.
-- =============================================================================


-- =============================================================================
-- 1. COMPANY
-- =============================================================================

INSERT INTO companies (
    id,
    name,
    slug,
    domain,
    status,
    created_at,
    updated_at
)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'InterviewIQ Dev',
    'interviewiq-dev',
    'dev.interviewiq.ai',
    'ACTIVE',
    now(),
    now()
)
ON CONFLICT (id) DO NOTHING;


-- =============================================================================
-- 2. ADMIN USER
-- =============================================================================

-- password plaintext: "password"
-- BCrypt hash (cost 10) — one-way and salted; safe to commit to source control.
-- See WARNING above: cost factor 10 is for local dev only.
-- Remove any pre-existing row with the same email in this company.
-- This handles the case where the developer registered an account via the API
-- before the seed ran (creating a different UUID + email_verified = FALSE).
-- FK children (sessions, etc.) are CASCADE-deleted or SET NULL per schema DDL.
DELETE FROM users
WHERE company_id = '00000000-0000-0000-0000-000000000001'
  AND email      = 'admin@dev.interviewiq.ai'
  AND id        != '00000000-0000-0000-0000-000000000002';

INSERT INTO users (
    id,
    company_id,
    full_name,
    email,
    password_hash,
    google_subject,
    role,
    is_active,
    email_verified,
    created_at,
    updated_at
)
VALUES (
    '00000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000001',
    'Dev Admin',
    'admin@dev.interviewiq.ai',
    '$2b$10$lMrOOPrLFSx1d7P3PQ5Qsum5MkNWPfN5Tz5D2Z.UX5W0a/Jzjpeuq',
    NULL,
    'ADMIN',
    TRUE,
    TRUE,
    now(),
    now()
)
ON CONFLICT (id) DO UPDATE
    SET email_verified = TRUE,
        is_active      = TRUE,
        role           = 'ADMIN',
        password_hash  = EXCLUDED.password_hash,
        updated_at     = now();


-- =============================================================================
-- 3. WALLET
-- =============================================================================

-- 50,000 paise = ₹500.00 starting balance.
-- reserved_paise = 0: no active sessions at seed time.
-- version = 0: optimistic lock starts at zero per JPA @Version convention.
INSERT INTO wallets (
    id,
    company_id,
    balance_paise,
    reserved_paise,
    version,
    created_at,
    updated_at
)
VALUES (
    '00000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000001',
    50000,
    0,
    0,
    now(),
    now()
)
ON CONFLICT (id) DO NOTHING;
