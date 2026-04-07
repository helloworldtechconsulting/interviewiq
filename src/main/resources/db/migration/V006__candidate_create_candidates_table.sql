-- =============================================================================
-- V006__candidate_create_candidates_table.sql
-- depends on: V001__company_create_companies_table.sql
--             V005__job_create_job_openings_table.sql
-- =============================================================================

CREATE TABLE candidates (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    company_id     UUID         NOT NULL,
    job_opening_id UUID         NOT NULL,
    email          VARCHAR(255) NOT NULL,

    -- NOT NULL: required for email templates, interview reports, and every UI
    -- surface that displays the candidate's name. A blank name is not acceptable
    -- in a professional context and indicates an incomplete data entry.
    full_name      VARCHAR(255) NOT NULL,

    -- S3 object key for the uploaded resume file.
    -- The raw file is preserved for download; parsed text is extracted to resume_text.
    resume_s3_key  VARCHAR(512),

    -- Extracted plain-text content of the resume. NULL while extraction is PENDING
    -- or IN_PROGRESS. Used as the second input to the AI question generation prompt,
    -- personalising questions to this specific candidate's background.
    -- Without this field, questions reflect only the JD — the core product
    -- differentiator ("questions crafted from JD + resume") would be broken.
    resume_text    TEXT,

    -- Tracks the async Apache Tika extraction pipeline state for the resume file.
    -- PENDING    → resume uploaded, extraction not yet started
    -- IN_PROGRESS → extraction job is running
    -- DONE       → resume_text is populated and ready for question generation
    -- FAILED     → extraction error; session can proceed with JD-only questions
    resume_extraction_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',

    -- NOTE: no status column on candidates. Session status is the authoritative
    -- state tracker (see interview_sessions.status). A dual status column here
    -- would create a two-write obligation on every state transition with no DB-level
    -- enforcement keeping them in sync. The candidate list view derives display
    -- status by joining to interview_sessions.
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_candidates
        PRIMARY KEY (id),

    -- enables composite FK from child tables (e.g. interview_sessions) to enforce
    -- that a referenced candidate belongs to the same company as the child row
    CONSTRAINT uq_candidates_company_id_id
        UNIQUE (company_id, id),

    CONSTRAINT fk_candidates_companies
        FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE RESTRICT,

    -- composite FK: guarantees the referenced job opening belongs to the same
    -- company as the candidate — enforces cross-tenant integrity at the DB level.
    -- requires uq_job_openings_company_id_id on job_openings(company_id, id) — added in V005
    CONSTRAINT fk_candidates_job_openings
        FOREIGN KEY (company_id, job_opening_id) REFERENCES job_openings (company_id, id) ON DELETE RESTRICT,

    -- a candidate can only be invited once per job opening
    CONSTRAINT uq_candidates_job_email
        UNIQUE (job_opening_id, email),

    -- mirror the lowercase invariant enforced on users.email and otp_records.email
    CONSTRAINT ck_candidates_email_lowercase
        CHECK (email = lower(email)),

    CONSTRAINT ck_candidates_resume_extraction_status_valid
        CHECK (resume_extraction_status IN ('PENDING', 'IN_PROGRESS', 'DONE', 'FAILED'))
);
