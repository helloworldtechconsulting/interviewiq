-- =============================================================================
-- V052 — Question count is per-tier, not a fixed 8–12 range
--
-- V024 encoded "the 8–12 question business rule" as a CHECK constraint. That was
-- the March design's single question-count range, and PRD v2.1 §7.2.1 replaces
-- it with four per-job tiers:
--
--   QUICK           8 questions
--   STANDARD       15 questions
--   IN_DEPTH       20 questions
--   COMPREHENSIVE  26 questions
--
-- Left as it was, the constraint would reject every tier except Quick — the
-- exact divergence the PRD's own audit records as "a single hardcoded duration
-- and question-count range".
--
-- The replacement ties the count to the session's own duration_tier, which lives
-- on the same row and is therefore available to a CHECK. A tolerance is allowed
-- below the target: §7.5.7 specifies a circuit-breaker fallback to the opening's
-- cached question bank when the LLM provider is unavailable, and a fallback bank
-- may be slightly short. Exceeding the tier's count is not tolerated — that would
-- push the interview past its hard timer.
-- =============================================================================

ALTER TABLE interview_sessions
    DROP CONSTRAINT IF EXISTS ck_interview_sessions_questions_json_count;

ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_questions_count_matches_tier
        CHECK (
            questions_json IS NULL
            OR jsonb_array_length(questions_json) BETWEEN
                   CASE duration_tier
                       WHEN 'QUICK'         THEN 6
                       WHEN 'STANDARD'      THEN 12
                       WHEN 'IN_DEPTH'      THEN 16
                       WHEN 'COMPREHENSIVE' THEN 20
                   END
               AND
                   CASE duration_tier
                       WHEN 'QUICK'         THEN 8
                       WHEN 'STANDARD'      THEN 15
                       WHEN 'IN_DEPTH'      THEN 20
                       WHEN 'COMPREHENSIVE' THEN 26
                   END
        );
