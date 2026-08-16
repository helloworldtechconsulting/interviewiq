-- =============================================================================
-- V046 — Per-answer persistence
--
-- PRD v2.1 §10 (SessionAnswer) and §7.5.7. Answers were previously folded into
-- the session's questions_json blob. They become rows because two requirements
-- need them to be:
--
--   * "If a candidate drops off mid-interview, every answered question is
--     already persisted" (§7.5.7). Each answer.submit writes one row as it
--     arrives, rather than rewriting a whole JSON document.
--
--   * The report must carry per-question narrative evidence in which every
--     claim cites a specific answer (§7.6). Evidence is anchored to an answer
--     row, so a claim can point at something durable.
--
-- question_source is denormalised onto the answer on purpose. The report shows a
-- source label on every employer-supplied question, because a recruiter reading
-- a low Technical score needs to know whether it came from our questions or
-- theirs — and that must stay true even if the employer later edits or deletes
-- the question from the job's bank.
-- =============================================================================

CREATE TABLE session_answers (
    id               UUID        NOT NULL DEFAULT gen_random_uuid(),
    company_id       UUID        NOT NULL,
    session_id       UUID        NOT NULL,

    question_index   INTEGER     NOT NULL,
    question_text    TEXT        NOT NULL,

    -- AI | EMPLOYER — see §7.5.8. Denormalised deliberately; see header.
    question_source  VARCHAR(20) NOT NULL DEFAULT 'AI',

    -- A follow-up pushed by the followup workflow rather than drawn from the
    -- pre-generated bank. Follow-ups share the index of the question they
    -- follow, distinguished by this flag.
    is_follow_up     BOOLEAN     NOT NULL DEFAULT FALSE,

    -- The accumulated browser SpeechRecognition transcript for this answer.
    -- NULL when the question was skipped after 90 seconds of silence (§7.5.7).
    transcript_text  TEXT,

    duration_seconds INTEGER,

    -- 0–10, written by the evaluation worker after the interview ends. NULL
    -- until the session is scored.
    score            SMALLINT,

    -- TRUE when the question was marked Skipped rather than answered.
    skipped          BOOLEAN     NOT NULL DEFAULT FALSE,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_session_answers
        PRIMARY KEY (id),

    -- One row per question per session, counting a follow-up as distinct from
    -- the question it follows. This is also the idempotency guard on
    -- answer.submit: a WebSocket retry after a dropped ack cannot create a
    -- second answer row.
    CONSTRAINT uq_session_answers_session_question
        UNIQUE (session_id, question_index, is_follow_up),

    CONSTRAINT fk_session_answers_sessions
        FOREIGN KEY (company_id, session_id)
            REFERENCES interview_sessions (company_id, id) ON DELETE CASCADE,

    CONSTRAINT ck_session_answers_question_index_non_negative
        CHECK (question_index >= 0),

    CONSTRAINT ck_session_answers_question_text_not_empty
        CHECK (length(trim(question_text)) > 0),

    CONSTRAINT ck_session_answers_question_source_valid
        CHECK (question_source IN ('AI', 'EMPLOYER')),

    CONSTRAINT ck_session_answers_duration_non_negative
        CHECK (duration_seconds IS NULL OR duration_seconds >= 0),

    CONSTRAINT ck_session_answers_score_valid
        CHECK (score IS NULL OR score BETWEEN 0 AND 10),

    -- A skipped question has no transcript, and an answered one is not skipped.
    CONSTRAINT ck_session_answers_skipped_has_no_transcript
        CHECK (NOT skipped OR transcript_text IS NULL)
);

-- The report and the evaluation worker both read a session's answers in order.
CREATE INDEX idx_session_answers_session_order
    ON session_answers (session_id, question_index, is_follow_up);
