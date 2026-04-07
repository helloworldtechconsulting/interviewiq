-- =============================================================================
-- V004__auth_create_otp_records_table.sql
-- depends on: V001__company_create_companies_table.sql
--             V002__auth_create_users_table.sql
--             V003__auth_create_refresh_tokens_table.sql
-- note: intentionally references no FK — OTPs are issued before a user row
--       may exist (e.g. EMAIL_VERIFICATION during registration flow, and
--       CANDIDATE_AUTH where the candidate has no users table row at all)
-- =============================================================================

CREATE TABLE otp_records (
    id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    email      VARCHAR(255) NOT NULL,
    purpose    VARCHAR(50)  NOT NULL,
    otp_hash   VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    used       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_otp_records
        PRIMARY KEY (id),

    -- mirror the lowercase invariant enforced on users.email
    CONSTRAINT ck_otp_records_email_lowercase
        CHECK (email = lower(email)),

    -- OTP purposes:
    --   LOGIN               — password-based login second factor
    --   PASSWORD_RESET      — account recovery flow
    --   EMAIL_VERIFICATION  — verifies a new user's email before first login
    --   CANDIDATE_AUTH      — email OTP authentication for candidates accessing
    --                         their interview link (no users table row exists)
    CONSTRAINT ck_otp_records_purpose_valid
        CHECK (purpose IN ('LOGIN', 'PASSWORD_RESET', 'EMAIL_VERIFICATION', 'CANDIDATE_AUTH')),

    -- expiry must be strictly after row creation; guards against clock-skew bugs
    CONSTRAINT ck_otp_records_future_expiry
        CHECK (expires_at > created_at)
);
