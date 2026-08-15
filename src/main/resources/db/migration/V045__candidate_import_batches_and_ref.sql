-- =============================================================================
-- V045 — Bulk candidate import by CSV, and the opaque candidate reference
--
-- Two changes that both concern how a candidate is represented.
--
-- 1. candidate_ref (PRD §7.5.6, §10). PII redaction is mandatory on every
--    outbound LLM call, for every workflow. Candidate name, email and phone are
--    stripped from the payload and this opaque reference is passed instead;
--    identity is re-attached locally when the report is persisted. The
--    evaluation model does not need to know who the candidate is in order to
--    score an answer about Spring Boot. Storing the reference rather than
--    deriving it per call means a redacted transcript stays correlatable to one
--    candidate across retries and across the two vendors in shadow mode.
--
-- 2. Bulk import (PRD §7.3.1). A recruiter running a hiring drive will not add
--    fifty candidates one at a time. The flow is upload CSV → column mapping →
--    validation preview → confirm → import, capped at 200 per import.
--
--    THE CRITICAL RULE is the atomic batch reservation. A 50-candidate import
--    that runs out of money at candidate 38 is a support ticket and a
--    half-imported opening. reserved_amount_paise records the whole-batch
--    reservation taken up front; if the balance will not cover the batch, the
--    entire import is refused with a top-up prompt rather than failing partway.
-- =============================================================================

-- ── Opaque LLM-payload reference ────────────────────────────────────────────
ALTER TABLE candidates
    ADD COLUMN candidate_ref VARCHAR(64);

-- Backfill existing rows. gen_random_uuid() is per-row here, not a constant.
UPDATE candidates
SET candidate_ref = 'cand_' || replace(gen_random_uuid()::text, '-', '')
WHERE candidate_ref IS NULL;

ALTER TABLE candidates
    ALTER COLUMN candidate_ref SET NOT NULL;

ALTER TABLE candidates
    ADD CONSTRAINT uq_candidates_candidate_ref UNIQUE (candidate_ref);

-- ── Import batches ──────────────────────────────────────────────────────────
CREATE TABLE candidate_import_batches (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid(),
    company_id           UUID         NOT NULL,
    job_opening_id       UUID         NOT NULL,
    uploaded_by          UUID,

    file_name            VARCHAR(255) NOT NULL,

    -- The user-confirmed mapping from CSV header to candidate field. Mapping is
    -- explicit and user-driven — the UI proposes a mapping from the header row
    -- and the user confirms or corrects it. We do not guess silently (§7.3.1),
    -- so the accepted mapping is recorded rather than re-inferred.
    column_mapping_jsonb JSONB,

    row_count            INTEGER      NOT NULL DEFAULT 0,
    valid_count          INTEGER      NOT NULL DEFAULT 0,
    duplicate_count      INTEGER      NOT NULL DEFAULT 0,
    invalid_count        INTEGER      NOT NULL DEFAULT 0,

    -- The whole-batch wallet reservation. Taken atomically before any candidate
    -- row is written; released if the import is rejected or fails.
    reserved_amount_paise BIGINT      NOT NULL DEFAULT 0,

    -- VALIDATING → parsing and checking rows
    -- PREVIEW    → counts computed, awaiting recruiter confirmation
    -- IMPORTING  → reservation taken, candidate rows being written
    -- COMPLETED  → all rows imported
    -- REJECTED   → refused (insufficient balance, or recruiter abandoned)
    status               VARCHAR(20)  NOT NULL DEFAULT 'VALIDATING',

    -- Per-row validation detail shown in the preview, so the recruiter can fix
    -- or skip rows before committing.
    validation_errors_jsonb JSONB,

    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_candidate_import_batches
        PRIMARY KEY (id),

    CONSTRAINT uq_candidate_import_batches_company_id_id
        UNIQUE (company_id, id),

    CONSTRAINT fk_candidate_import_batches_job_openings
        FOREIGN KEY (company_id, job_opening_id)
            REFERENCES job_openings (company_id, id) ON DELETE CASCADE,

    CONSTRAINT fk_candidate_import_batches_users
        FOREIGN KEY (uploaded_by) REFERENCES users (id) ON DELETE SET NULL,

    CONSTRAINT ck_candidate_import_batches_status_valid
        CHECK (status IN ('VALIDATING', 'PREVIEW', 'IMPORTING', 'COMPLETED', 'REJECTED')),

    -- Cap 200 per import, matching the existing per-opening limit (§7.3.1).
    CONSTRAINT ck_candidate_import_batches_row_count_within_cap
        CHECK (row_count >= 0 AND row_count <= 200),

    CONSTRAINT ck_candidate_import_batches_counts_non_negative
        CHECK (valid_count >= 0 AND duplicate_count >= 0 AND invalid_count >= 0),

    -- The three outcome counts must account for every row read.
    CONSTRAINT ck_candidate_import_batches_counts_sum_to_row_count
        CHECK (valid_count + duplicate_count + invalid_count <= row_count),

    CONSTRAINT ck_candidate_import_batches_reserved_non_negative
        CHECK (reserved_amount_paise >= 0),

    CONSTRAINT ck_candidate_import_batches_file_name_not_empty
        CHECK (length(trim(file_name)) > 0),

    CONSTRAINT ck_candidate_import_batches_column_mapping_is_object
        CHECK (column_mapping_jsonb IS NULL OR jsonb_typeof(column_mapping_jsonb) = 'object'),

    CONSTRAINT ck_candidate_import_batches_validation_errors_is_array
        CHECK (validation_errors_jsonb IS NULL OR jsonb_typeof(validation_errors_jsonb) = 'array')
);

CREATE INDEX idx_candidate_import_batches_company_created
    ON candidate_import_batches (company_id, created_at DESC);

-- ── Link imported candidates back to their batch ────────────────────────────
ALTER TABLE candidates
    ADD COLUMN import_batch_id UUID;

ALTER TABLE candidates
    ADD CONSTRAINT fk_candidates_import_batches
        FOREIGN KEY (company_id, import_batch_id)
            REFERENCES candidate_import_batches (company_id, id) ON DELETE SET NULL;

CREATE INDEX idx_candidates_import_batch
    ON candidates (import_batch_id)
    WHERE import_batch_id IS NOT NULL;
