-- =============================================================================
-- V005__job_create_job_openings_table.sql
-- depends on: V001__company_create_companies_table.sql
--             V002__auth_create_users_table.sql
-- =============================================================================

CREATE TABLE job_openings (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    company_id      UUID         NOT NULL,
    created_by      UUID         NOT NULL,
    title           VARCHAR(255) NOT NULL,
    department      VARCHAR(255),
    location_type   VARCHAR(50),
    employment_type VARCHAR(50),

    -- S3 object key for the original uploaded JD file (PDF/DOCX).
    -- The raw file is preserved for download; parsed text is extracted to jd_text.
    jd_s3_key       VARCHAR(512),

    -- Extracted plain-text content of the job description, used as the primary
    -- input to the AI question generation pipeline. NULL while extraction is
    -- PENDING or IN_PROGRESS. Once set, this field eliminates the need to
    -- re-fetch and re-parse the S3 file on every question generation call.
    jd_text         TEXT,

    -- Tracks the async Apache Tika extraction pipeline state for the JD file.
    -- Session creation is BLOCKED until this reaches 'DONE'.
    -- PENDING    → file uploaded, extraction not yet started
    -- IN_PROGRESS → extraction job is running
    -- DONE       → jd_text is populated and ready for question generation
    -- FAILED     → extraction error; alert triggered; session creation blocked
    jd_extraction_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',

    status          VARCHAR(50)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_job_openings
        PRIMARY KEY (id),

    -- enables composite FK from child tables (e.g. candidates) to enforce
    -- that a referenced job opening belongs to the same company as the child row
    CONSTRAINT uq_job_openings_company_id_id
        UNIQUE (company_id, id),

    CONSTRAINT fk_job_openings_company
        FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE RESTRICT,

    -- composite FK: guarantees created_by user belongs to the same company as this
    -- job opening — enforces cross-tenant integrity at the database level.
    -- requires uq_users_company_id_id on users(company_id, id) — added in V002
    CONSTRAINT fk_job_openings_creator
        FOREIGN KEY (company_id, created_by) REFERENCES users (company_id, id) ON DELETE RESTRICT,

    CONSTRAINT ck_job_openings_status_valid
        CHECK (status IN ('ACTIVE', 'ARCHIVED', 'CLOSED')),

    CONSTRAINT ck_job_openings_location_type_valid
        CHECK (location_type IS NULL OR location_type IN ('REMOTE', 'ONSITE', 'HYBRID')),

    CONSTRAINT ck_job_openings_employment_type_valid
        CHECK (employment_type IS NULL OR employment_type IN ('FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERNSHIP')),

    CONSTRAINT ck_job_openings_jd_extraction_status_valid
        CHECK (jd_extraction_status IN ('PENDING', 'IN_PROGRESS', 'DONE', 'FAILED'))
);
