-- =============================================================================
-- V024__session_add_jsonb_check_constraints.sql
-- purpose: add JSONB structural CHECK constraints as the last line of defence
--          against malformed AI-generated content reaching the database.
--
-- DESIGN NOTE (M6 resolution):
--   questions_json and question_generation_status columns are now defined
--   directly in V007 so that the table DDL is fully self-describing.
--   This file contains only the CHECK constraints — the JSONB logic
--   (jsonb_typeof, jsonb_array_length, cross-field consistency) is placed
--   here so it can be reviewed independently of the column definitions.
--
-- CONSTRAINTS ADDED TO interview_sessions:
--   1. ck_interview_sessions_questions_json_is_array
--      Ensures questions_json is always a JSON array when present.
--
--   2. ck_interview_sessions_questions_json_count
--      Enforces the 8–12 question business rule at the DB layer.
--
--   3. ck_interview_sessions_questions_status_consistency
--      Cross-field guard: questions_json NULL unless question_generation_status
--      is DONE; non-NULL only when DONE.
--
-- CONSTRAINT ADDED TO webhook_events:
--   4. ck_webhook_events_payload_is_object
--      Webhooks are always JSON objects at the root level.
--
-- See migration plan Section 4.4 for full design rationale.
-- =============================================================================


-- =============================================================================
-- interview_sessions — JSONB structural constraints
-- =============================================================================

-- Ensure questions_json is always a JSON array when present.
-- Prevents the AI pipeline from storing a JSON object, string, or bare
-- number in place of the expected array of question objects.
ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_questions_json_is_array
        CHECK (
            questions_json IS NULL
            OR jsonb_typeof(questions_json) = 'array'
        );

-- Enforce the 8–12 question business rule at the DB layer.
-- If the LLM returns fewer than 8 or more than 12 questions, this constraint
-- rejects the write before the malformed payload reaches the interview bot.
ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_questions_json_count
        CHECK (
            questions_json IS NULL
            OR jsonb_array_length(questions_json) BETWEEN 8 AND 12
        );

-- Cross-field consistency: all valid state combinations are encoded.
--   (a) status = PENDING     → questions_json must be NULL
--   (b) status = IN_PROGRESS → questions_json must be NULL
--   (c) status = FAILED      → questions_json must be NULL
--   (d) status = DONE        → questions_json must NOT be NULL
-- question_generation_status is NOT NULL DEFAULT 'PENDING' (V007), so the
-- IS NULL arm has been removed — it is no longer a reachable state.
-- Prevents impossible states: DONE with no questions, or questions set while
-- generation is still pending.
ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_questions_status_consistency
        CHECK (
            (question_generation_status = 'DONE' AND questions_json IS NOT NULL)
            OR (question_generation_status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
                AND questions_json IS NULL)
        );


-- =============================================================================
-- webhook_events — payload structural constraint
-- =============================================================================

-- Webhooks from all providers (Razorpay, Recall.ai) are always JSON objects at
-- the root level — never arrays, strings, or bare scalars. Rejects any payload
-- that does not conform to the expected {key: value, ...} structure, catching
-- malformed or truncated provider payloads before application deserialization.
-- Note: the JSONB column is named payload_json in this schema (V012).
ALTER TABLE webhook_events
    ADD CONSTRAINT ck_webhook_events_payload_is_object
        CHECK (jsonb_typeof(payload_json) = 'object');
