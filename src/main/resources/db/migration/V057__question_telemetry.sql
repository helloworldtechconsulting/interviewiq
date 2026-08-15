-- =============================================================================
-- V057 — per-question telemetry and auto-retirement (INTIQ-93, item 4)
--
-- WHY THIS IS THE PART THAT MATTERED
--
-- INTIQ-93 removed recruiter review of questions. That decision rests entirely
-- on a claim: that automated controls can substitute for the human who used to
-- look. Three of the four controls shipped — the critic pass, the deterministic
-- prohibited-topic filter, and automatic core selection. All three are
-- *up-front* gates.
--
-- Auto-retirement is the only one that operates after questions ship, and it is
-- the one that makes the system self-correcting. Without it the mitigation reads
-- "prompt guardrails, plus a hard filter, plus nothing" — and a question that
-- reads beautifully, passes every gate, and discriminates nothing sits in the
-- bank indefinitely being asked of candidate after candidate.
--
-- THE SIGNAL NOBODY WOULD SPOT BY READING
--
-- Three of the four signals are legible to a human: a question everyone skips is
-- confusing, one that draws five-word answers is closed or vague, one candidates
-- flag is self-evident.
--
-- Score variance is not. A question every candidate scores 7/10 on tells you
-- nothing about any of them — it is a well-written, on-topic, perfectly sensible
-- question that costs 90 seconds of every interview and contributes zero to the
-- ranking the product is bought for. You cannot see that by reading the
-- question. You can only see it in the distribution of what it produced, which
-- is what this table exists to accumulate.
--
-- WHY WELFORD RATHER THAN STORING SCORES
--
-- Variance is kept as a running (count, mean, M2) triple rather than by storing
-- every score and computing on read. One row per question regardless of how many
-- candidates answer it, no unbounded growth, and no read-time aggregation over a
-- table that would otherwise gain a row per answer per question forever.
-- =============================================================================

CREATE TABLE IF NOT EXISTS question_telemetry (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    job_opening_id          UUID        NOT NULL REFERENCES job_openings(id) ON DELETE CASCADE,

    -- The bank-local id ("q17"), not a global one. A question only has meaning
    -- inside the bank that generated it: the same text regenerated for another
    -- opening is a different question asked of different candidates against a
    -- different job description, and pooling their statistics would be wrong.
    bank_question_id        VARCHAR(50) NOT NULL,

    -- Kept for the console, so a retired question can be read without joining
    -- back through the bank JSON to find out what it said.
    question_text           TEXT        NOT NULL,

    times_asked             INT         NOT NULL DEFAULT 0,
    times_skipped           INT         NOT NULL DEFAULT 0,
    short_answers           INT         NOT NULL DEFAULT 0,
    candidate_flags         INT         NOT NULL DEFAULT 0,

    -- Welford's online variance. mean and m2 are DOUBLE PRECISION because they
    -- are statistics rather than money — the exactness rules that apply to paise
    -- do not apply here, and float is the right tool for a running variance.
    scored_count            INT              NOT NULL DEFAULT 0,
    score_mean              DOUBLE PRECISION NOT NULL DEFAULT 0,
    score_m2                DOUBLE PRECISION NOT NULL DEFAULT 0,

    retired_at              TIMESTAMPTZ,
    retired_reason          VARCHAR(50),

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_question_telemetry_job_question
        UNIQUE (job_opening_id, bank_question_id),

    CONSTRAINT ck_question_telemetry_retired_reason
        CHECK (retired_reason IS NULL OR retired_reason IN (
            'HIGH_SKIP_RATE',
            'SHORT_ANSWERS',
            'NO_SCORE_VARIANCE',
            'CANDIDATE_FLAGGED',
            'MANUAL'
        )),

    -- A retirement without a reason is unauditable, and this table exists partly
    -- to be audited. Enforced rather than left to the application.
    CONSTRAINT ck_question_telemetry_retired_pair
        CHECK ((retired_at IS NULL) = (retired_reason IS NULL)),

    CONSTRAINT ck_question_telemetry_counts_sane
        CHECK (times_skipped <= times_asked AND short_answers <= times_asked)
);

-- The retirement sweep asks "which live questions have enough data to judge".
-- Partial index on the live ones: retired questions are never re-evaluated, and
-- they accumulate without bound while the live set stays roughly constant.
CREATE INDEX IF NOT EXISTS ix_question_telemetry_live
    ON question_telemetry (job_opening_id)
    WHERE retired_at IS NULL;

COMMENT ON TABLE question_telemetry IS
    'Per-question outcome statistics driving auto-retirement (INTIQ-93). One row per (opening, bank question).';

COMMENT ON COLUMN question_telemetry.score_m2 IS
    'Welford M2 — sum of squared deviations. Variance is m2/(scored_count-1). Near-zero variance means the question does not discriminate, which is the strongest and least visible retirement signal.';

-- ── The attribution link ─────────────────────────────────────────────────────
--
-- A per-question score is only known at evaluation time, which is minutes after
-- the answer was given and in a different service. Without recording which bank
-- question produced an answer, the score cannot be folded back into that
-- question's variance — and variance is the whole point of the table above.
--
-- Stored rather than re-derived from the session's questions JSON. Which bank
-- question an answer came from is a durable fact about that answer; deriving it
-- later would depend on the questions JSON keeping its shape forever, and on the
-- bank still existing.
--
-- Null for follow-ups (generated live, never reused) and employer questions
-- (theirs to judge, not ours to retire).
ALTER TABLE session_answers
    ADD COLUMN IF NOT EXISTS bank_question_id VARCHAR(50);

COMMENT ON COLUMN session_answers.bank_question_id IS
    'Bank-local id of the question this answer responded to, or null for follow-ups and employer questions. Links a score back to the question whose variance it informs.';

CREATE INDEX IF NOT EXISTS ix_session_answers_bank_question
    ON session_answers (bank_question_id)
    WHERE bank_question_id IS NOT NULL;
