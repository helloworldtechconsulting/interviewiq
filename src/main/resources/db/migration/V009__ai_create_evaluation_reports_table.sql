-- =============================================================================
-- V009__ai_create_evaluation_reports_table.sql
-- depends on: V001__company_create_companies_table.sql
--             V007__session_create_interview_sessions_table.sql
-- =============================================================================

CREATE TABLE evaluation_reports (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    company_id      UUID        NOT NULL,
    session_id      UUID        NOT NULL,

    -- Scalar score columns: typed for aggregation (AVG, ORDER BY, chart rendering).
    -- These must NOT live in evaluation_json — the dashboard charts query
    -- AVG(technical_score) grouped by company_id and job_opening_id.
    -- All nullable: populated asynchronously by the AI evaluation pipeline.
    overall_score           SMALLINT,   -- 0–100 composite score
    technical_score         SMALLINT,   -- 0–10 per-dimension score
    communication_score     SMALLINT,   -- 0–10 per-dimension score
    relevance_score         SMALLINT,   -- 0–10 (answer relevance to the question)
    problem_solving_score   SMALLINT,   -- 0–10 per-dimension score

    recommendation  VARCHAR(50),

    -- Async pipeline state machine:
    --   PENDING     → session just ended, evaluation not yet triggered
    --   IN_PROGRESS → GPT-4o call in flight
    --   DONE        → all score columns and evaluation_json populated
    --   FAILED      → pipeline error after generation_attempts exhausted
    generation_status   VARCHAR(50) NOT NULL DEFAULT 'PENDING',

    -- Retry counter: incremented on each pipeline attempt.
    -- The EvaluationService stops retrying after a configurable maximum (e.g. 3).
    generation_attempts INT         NOT NULL DEFAULT 0,

    -- S3 key for the full transcript of the interview. The transcript is too large
    -- to embed in the DB (5,000–10,000 words per interview); only the key is stored.
    -- evaluation_json holds the structured per-question assessment; this holds the
    -- raw verbatim text for human review and compliance download.
    transcript_s3_key   VARCHAR(512),

    -- Stores the structured per-question evaluation as a JSON document.
    -- This is the narrative AI output — not aggregated, not queryable by field.
    -- Scalar queryable scores (above) are always promoted to typed columns.
    evaluation_json JSONB,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_evaluation_reports
        PRIMARY KEY (id),

    -- one evaluation per session — enforced at the DB level
    CONSTRAINT uq_evaluation_reports_session
        UNIQUE (session_id),

    -- composite FK: guarantees the session belongs to the same company.
    -- CASCADE: evaluation is owned by the session — if the session is deleted
    -- (which must not happen in production; sessions are financial records),
    -- the evaluation goes with it.
    -- requires uq_interview_sessions_company_id_id on interview_sessions(company_id, id) — added in V007
    CONSTRAINT fk_evaluation_reports_sessions
        FOREIGN KEY (company_id, session_id) REFERENCES interview_sessions (company_id, id) ON DELETE CASCADE,

    -- Overall score: 0–100 composite, populated once pipeline is DONE
    CONSTRAINT ck_evaluation_reports_overall_score_valid
        CHECK (overall_score IS NULL OR overall_score BETWEEN 0 AND 100),

    -- Per-dimension scores: 0–10 scale
    CONSTRAINT ck_evaluation_reports_technical_score_valid
        CHECK (technical_score IS NULL OR technical_score BETWEEN 0 AND 10),

    CONSTRAINT ck_evaluation_reports_communication_score_valid
        CHECK (communication_score IS NULL OR communication_score BETWEEN 0 AND 10),

    CONSTRAINT ck_evaluation_reports_relevance_score_valid
        CHECK (relevance_score IS NULL OR relevance_score BETWEEN 0 AND 10),

    CONSTRAINT ck_evaluation_reports_problem_solving_score_valid
        CHECK (problem_solving_score IS NULL OR problem_solving_score BETWEEN 0 AND 10),

    CONSTRAINT ck_evaluation_reports_recommendation_valid
        CHECK (recommendation IS NULL OR recommendation IN ('STRONG_HIRE', 'HIRE', 'NO_HIRE', 'STRONG_NO_HIRE')),

    -- a recommendation without a score is logically incoherent
    CONSTRAINT ck_evaluation_reports_score_required_with_recommendation
        CHECK (recommendation IS NULL OR overall_score IS NOT NULL),

    CONSTRAINT ck_evaluation_reports_generation_status_valid
        CHECK (generation_status IN ('PENDING', 'IN_PROGRESS', 'DONE', 'FAILED')),

    CONSTRAINT ck_evaluation_reports_generation_attempts_non_negative
        CHECK (generation_attempts >= 0)
);
