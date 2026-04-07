-- =============================================================================
-- V002__auth_create_users_table.sql
-- depends on: V001__company_create_companies_table.sql
-- =============================================================================

CREATE TABLE users (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    company_id      UUID         NOT NULL,

    -- Required for every user; displayed in the UI on team pages, job opening
    -- "created by" attributions, and in audit log human-readable summaries.
    full_name       VARCHAR(255) NOT NULL,

    email           VARCHAR(255) NOT NULL,
    password_hash   VARCHAR(255),
    google_subject  VARCHAR(255),
    role            VARCHAR(50)  NOT NULL,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Email verification gate: FALSE until the user submits a valid OTP from the
    -- EMAIL_VERIFICATION flow. The AuthService MUST include email_verified = TRUE
    -- in every login query. Unverified users are blocked from logging in.
    email_verified  BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_users
        PRIMARY KEY (id),

    CONSTRAINT fk_users_companies
        FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE RESTRICT,

    -- Scoped to company: the same email can exist in two different companies.
    -- The login query MUST always scope to company_id — never query by email alone.
    CONSTRAINT uq_users_company_email
        UNIQUE (company_id, email),

    -- Enables composite FK from child tables (e.g. job_openings) to enforce
    -- that a referenced user belongs to the same company as the child row.
    CONSTRAINT uq_users_company_id_id
        UNIQUE (company_id, id),

    CONSTRAINT ck_users_role_valid
        CHECK (role IN ('ADMIN', 'RECRUITER', 'VIEWER')),

    -- Enforce lowercase storage; normalise at the application layer before INSERT/UPDATE.
    CONSTRAINT ck_users_email_lowercase
        CHECK (email = lower(email)),

    -- At least one authentication method must be present.
    -- Google OAuth users: google_subject NOT NULL, password_hash NULL.
    -- Password users: password_hash NOT NULL, google_subject NULL.
    -- Linked accounts: both NOT NULL — satisfies either branch.
    CONSTRAINT ck_users_auth_method
        CHECK (
            password_hash IS NOT NULL OR google_subject IS NOT NULL
        )
);
