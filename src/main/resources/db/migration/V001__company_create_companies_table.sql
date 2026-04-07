-- =============================================================================
-- V001__company_create_companies_table.sql
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE companies (
    id         UUID         NOT NULL DEFAULT gen_random_uuid(),
    name       VARCHAR(255) NOT NULL,

    -- URL-safe platform identifier used for routing (/company/{slug}/dashboard)
    -- and as the primary human-readable company reference throughout the system.
    -- Generated from the company name at registration time; immutable after creation.
    -- Must be globally unique across all companies on the platform.
    slug       VARCHAR(100) NOT NULL,

    -- Optional: the company's email domain (e.g. acme.com) used for domain-based
    -- company resolution during Google OAuth registration. Not the primary
    -- identifier — slug is. Nullable because not all companies have a domain,
    -- and domain matching is supplementary to slug-based routing.
    domain     VARCHAR(255),

    status     VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_companies
        PRIMARY KEY (id),

    CONSTRAINT uq_companies_slug
        UNIQUE (slug),

    CONSTRAINT uq_companies_domain
        UNIQUE (domain),

    CONSTRAINT ck_companies_status_valid
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED'))
);
