-- =============================================================================
-- V037__storage_add_object_types_and_indexes.sql
-- purpose: (1) extend storage_objects.object_type CHECK constraint to include
--              COMPANY_LOGO and RECORDING — two new S3 object categories
--              introduced by V027 (companies.logo_s3_key) and V032
--              (interview_sessions.recording_s3_key) respectively.
--          (2) add supplemental indexes for new columns added in V027–V036.
--
-- PART 1: storage_objects.object_type extension
-- ──────────────────────────────────────────────
-- Current allowed values (V013):
--   RESUME, JOB_DESCRIPTION, TRANSCRIPT, EVALUATION_EXPORT
--
-- Added:
--   COMPANY_LOGO  — logo uploaded via CompanyController.getLogoUploadUrl();
--                   stored under companies.logo_s3_key (V027)
--   RECORDING     — session video recording uploaded by the Recall.ai bot;
--                   stored under interview_sessions.recording_s3_key (V032);
--                   S3 lifecycle rule: auto-deleted after 7 days
--
-- PostgreSQL does not support modifying a CHECK constraint in-place; it must
-- be dropped and re-added. Existing rows are unaffected — they carry only
-- the four original type values, all of which are still valid.
--
-- PART 2: supplemental indexes
-- ─────────────────────────────
-- Covers new columns added in V027–V036 that need query-path support.
-- =============================================================================


-- =============================================================================
-- PART 1 — storage_objects.object_type CHECK constraint update
-- =============================================================================

-- Drop the existing CHECK constraint (V013 name: ck_storage_objects_object_type_valid)
ALTER TABLE storage_objects
    DROP CONSTRAINT ck_storage_objects_object_type_valid;

-- Re-add with extended enum values
ALTER TABLE storage_objects
    ADD CONSTRAINT ck_storage_objects_object_type_valid
        CHECK (object_type IS NULL OR object_type IN (
            'RESUME',
            'JOB_DESCRIPTION',
            'TRANSCRIPT',
            'EVALUATION_EXPORT',
            'COMPANY_LOGO',
            'RECORDING'
        ));


-- =============================================================================
-- PART 2 — supplemental indexes for new columns
-- =============================================================================

-- ── interview_sessions — cancelled_at ────────────────────────────────────────
-- Cancellation analytics and refund-eligibility window queries:
--   WHERE status = 'CANCELLED' AND cancelled_at > NOW() - INTERVAL '7 days'
-- partial: only CANCELLED sessions carry a cancelled_at value
CREATE INDEX idx_interview_sessions_cancelled_at
    ON interview_sessions (cancelled_at DESC)
    WHERE status = 'CANCELLED';


-- ── interview_sessions — recording_s3_key ────────────────────────────────────
-- S3 lifecycle reconciliation job: find sessions with recordings that may
-- need their key cleared after the 7-day S3 auto-delete.
-- partial: NULL recording_s3_key rows (not yet recorded) are excluded
CREATE INDEX idx_interview_sessions_recording_s3_key
    ON interview_sessions (recording_s3_key)
    WHERE recording_s3_key IS NOT NULL;


-- ── availability_slots — for recruiter-facing slot management page ────────────
-- Already covered by V033 indexes:
--   idx_availability_slots_job_opening_status
--   idx_availability_slots_expiry_scan
--   idx_availability_slots_company_job_opening


-- ── wallet_transactions — razorpay_payment_id ────────────────────────────────
-- The UNIQUE constraint uq_wallet_transactions_razorpay_payment_id (V031)
-- creates a B-tree index that serves the webhook correlation lookup:
--   WHERE razorpay_payment_id = ?
-- No additional index needed (documented explicitly to prevent duplication).


-- ── companies — size band (pricing/analytics) ─────────────────────────────────
CREATE INDEX idx_companies_size
    ON companies (size)
    WHERE size IS NOT NULL;


-- ── job_openings — experience range (candidate matching) ─────────────────────
-- Recruiter filter: "show jobs requiring 3–5 years of experience"
--   WHERE experience_min <= ? AND (experience_max IS NULL OR experience_max >= ?)
-- Both columns are indexed separately; the planner will use either or both
-- depending on the selectivity of the specific filter values.
CREATE INDEX idx_job_openings_experience_min
    ON job_openings (experience_min)
    WHERE experience_min IS NOT NULL;

CREATE INDEX idx_job_openings_experience_max
    ON job_openings (experience_max)
    WHERE experience_max IS NOT NULL;
