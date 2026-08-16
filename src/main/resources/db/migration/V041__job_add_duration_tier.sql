-- =============================================================================
-- V041 — Interview duration tier per job opening
--
-- PRD v2.1 §7.2.1. Interview length becomes a per-job choice by the employer,
-- replacing the single hardcoded duration of the March design. The tier drives
-- three things: the generated question count, the server-side hard timer, and
-- how many 5-minute capacity buckets a booking occupies (§7.4.2).
--
-- All four tiers are priced at Rs.100 flat. The marginal cost of a longer
-- interview is LLM tokens measured in paise, and per-minute pricing would push
-- employers toward the wrong tier for the role — the tier is a product-fit
-- decision, not a monetisation lever. There is deliberately no price column here.
--
--   QUICK          20 min   8 questions
--   STANDARD       35 min  15 questions   (default)
--   IN_DEPTH       45 min  20 questions
--   COMPREHENSIVE  60 min  26 questions
--
-- The 60-minute ceiling from the API reference remains the absolute upper bound.
-- =============================================================================

ALTER TABLE job_openings
    ADD COLUMN duration_tier VARCHAR(20) NOT NULL DEFAULT 'STANDARD';

ALTER TABLE job_openings
    ADD CONSTRAINT ck_job_openings_duration_tier_valid
        CHECK (duration_tier IN ('QUICK', 'STANDARD', 'IN_DEPTH', 'COMPREHENSIVE'));
