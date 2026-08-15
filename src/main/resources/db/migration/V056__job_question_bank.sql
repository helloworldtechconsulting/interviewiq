-- =============================================================================
-- V056 — job-level question bank (INTIQ-17, two-stage generation)
--
-- THE TENSION THIS RESOLVES
--
-- Three product rules that cannot all hold under per-candidate generation:
--
--   1. Every candidate gets a different question set.
--   2. Questions are ~80% job description, ~20% resume.
--   3. Scores must be comparable across candidates for the same opening.
--
-- The JD is identical for all 25 candidates. Generate each set independently
-- from it and rule 2 produces 25 near-duplicate sets, violating rule 1. Push
-- for variety instead and rule 3 breaks — candidate A drawing easier questions
-- than candidate B scores higher for reasons unrelated to ability, which
-- destroys the ranking use case the product is bought for.
--
-- Resolution: a fixed core plus a rotating tail. The core is identical for
-- everyone on the opening (comparability); the tail is sampled per candidate
-- (variety, and anti-leak — candidates for one role compare notes within hours,
-- so a rotating tail means the third candidate cannot fully prepare from what
-- the first posted).
--
-- WHY THE BANK IS A COLUMN AND NOT A TABLE
--
-- A bank is written once per opening and read whole. There is no query that
-- wants one question of it without the others, no per-question row that outlives
-- the bank, and no cross-bank join. A table would add a join to every read and
-- a cascade to every job deletion in exchange for nothing. Per-question
-- telemetry (INTIQ-93) is a different shape and gets its own table.
--
-- COST — CHEAPER THAN WHAT IT REPLACES
--
-- Per-candidate full generation on a 25-candidate opening is 25 large calls.
-- Two-stage is one large call for the bank plus 25 small resume-anchored ones,
-- and the rotating tail is pure code with no call at all. The bank cost
-- amortises further the more candidates an opening has.
-- =============================================================================

ALTER TABLE job_openings
    ADD COLUMN IF NOT EXISTS question_bank_jsonb JSONB,
    ADD COLUMN IF NOT EXISTS question_bank_generated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS question_bank_status VARCHAR(50) NOT NULL DEFAULT 'PENDING';

ALTER TABLE job_openings
    DROP CONSTRAINT IF EXISTS ck_job_openings_question_bank_status;

ALTER TABLE job_openings
    ADD CONSTRAINT ck_job_openings_question_bank_status
        CHECK (question_bank_status IN ('PENDING', 'IN_PROGRESS', 'DONE', 'FAILED'));

-- Claim tracking, so the bank generator can use the same FOR UPDATE SKIP LOCKED
-- discipline as every other worker (§7.9). Without it, inviting a batch of
-- candidates the moment a JD finishes extracting would have several pods
-- generating the same bank concurrently — and unlike duplicate evaluation, the
-- last writer here silently changes which questions are "core", so two
-- candidates interviewed minutes apart would be scored on different core sets.
ALTER TABLE job_openings
    ADD COLUMN IF NOT EXISTS question_bank_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS question_bank_claimed_at TIMESTAMPTZ;

COMMENT ON COLUMN job_openings.question_bank_jsonb IS
    'The ~50-question bank for this opening: {"questions":[...],"coreQuestionIds":[...]}. Core ids are the comparability set asked of every candidate.';

COMMENT ON COLUMN job_openings.question_bank_status IS
    'PENDING until the JD is extracted and the bank generated. A job cannot invite candidates until DONE.';

-- The generator asks "which openings have an extracted JD but no bank yet".
-- Partial index so it stays proportional to pending work rather than to every
-- job ever created.
CREATE INDEX IF NOT EXISTS ix_job_openings_bank_pending
    ON job_openings (created_at)
    WHERE question_bank_status IN ('PENDING', 'IN_PROGRESS');
