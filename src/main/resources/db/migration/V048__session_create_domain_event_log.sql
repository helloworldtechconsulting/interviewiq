-- =============================================================================
-- V048 — Domain event log (INTIQ-98)
--
-- PRD v2.1, Implementation Architecture Decisions §2. This table is what
-- replaces a BPM engine. Flowable, Camunda and Temporal were evaluated and
-- rejected for phase 1; the decision turned on fit rather than build cost, and
-- the conclusion was that "the value worth having from BPM was always the
-- visibility, and visibility is the cheap part to replicate". This is that
-- replication: one self-referencing table giving a nested, drillable trace of
-- every session.
--
-- WHY IT EARNS ITS PLACE. When a customer disputes an interview, you open it and
-- see the whole flow — timings, external calls with their payloads, decision
-- points, and exactly where it failed. And because the trace is generated *by*
-- execution, it cannot drift from the code the way a BPMN diagram can.
--
-- DECISIONS ARE FIRST-CLASS. A DECISION span records the condition, its inputs,
-- the branch taken *and the branches not taken*. That is what makes a trace
-- explain itself rather than merely list events — "why did it not ask a
-- follow-up here?" is answerable.
--
-- RETENTION. Payloads are redacted and capped at 4 KB per side, retained 30 days
-- and then stripped. The span skeleton — timings, outcomes, decisions,
-- economics — is kept permanently. A trace stays useful long after the payloads
-- that would make it a PII liability have gone.
--
-- Span nesting is captured in the application with Java 21 ScopedValue, NOT
-- ThreadLocal, because the application runs on virtual threads (§6.2).
-- =============================================================================

CREATE TABLE session_events (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    company_id    UUID         NOT NULL,
    session_id    UUID         NOT NULL,

    -- Nesting. NULL marks a top-level subflow.
    parent_id     UUID,

    -- SUBFLOW  → a named unit of work containing other spans
    -- CALL     → an outbound call to something external
    -- DECISION → a branch point, with inputs and alternatives
    -- STATE    → a session state transition
    -- SIGNAL   → an inbound event (WebSocket message, webhook, timer fire)
    span_kind     VARCHAR(20)  NOT NULL,

    name          VARCHAR(200) NOT NULL,

    -- Ordering among siblings. Wall-clock start is not sufficient: spans on
    -- virtual threads can share a timestamp to the microsecond.
    sequence      INTEGER      NOT NULL DEFAULT 0,

    started_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ended_at      TIMESTAMPTZ,
    duration_ms   BIGINT,

    -- NULL while a span is still open.
    outcome       VARCHAR(20),

    -- ── CALL spans ──────────────────────────────────────────────────────────
    -- e.g. llm.question | llm.evaluation | s3.put | smtp.send | razorpay.order | ws
    target         VARCHAR(100),
    request_jsonb  JSONB,
    response_jsonb JSONB,
    http_status    INTEGER,
    error_code     VARCHAR(100),
    error_message  TEXT,
    attempt        INTEGER,

    -- ── DECISION spans ──────────────────────────────────────────────────────
    condition_expr         TEXT,          -- "answerWordCount < 5"
    condition_inputs_jsonb JSONB,         -- {"answerWordCount": 4}
    branch_taken           VARCHAR(100),  -- "ELABORATE"
    branches_available     JSONB,         -- ["ELABORATE","NEXT_QUESTION"]

    -- ── Economics ───────────────────────────────────────────────────────────
    -- Per-call token cost, which is how cost-per-interview (a launch KPI, target
    -- under Rs.20) is measured rather than estimated.
    tokens_in     INTEGER,
    tokens_out    INTEGER,
    cost_paise    BIGINT,

    correlation_id VARCHAR(100),

    -- Set when the 30-day sweep strips request/response payloads, so that a
    -- stripped span is distinguishable from one that never carried a payload.
    payloads_stripped_at TIMESTAMPTZ,

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_session_events
        PRIMARY KEY (id),

    CONSTRAINT fk_session_events_sessions
        FOREIGN KEY (company_id, session_id)
            REFERENCES interview_sessions (company_id, id) ON DELETE CASCADE,

    -- Self-referencing: the tree.
    CONSTRAINT fk_session_events_parent
        FOREIGN KEY (parent_id) REFERENCES session_events (id) ON DELETE CASCADE,

    CONSTRAINT ck_session_events_span_kind_valid
        CHECK (span_kind IN ('SUBFLOW', 'CALL', 'DECISION', 'STATE', 'SIGNAL')),

    CONSTRAINT ck_session_events_outcome_valid
        CHECK (outcome IS NULL OR outcome IN ('OK', 'FAILED', 'RETRY', 'SKIPPED', 'TIMEOUT')),

    CONSTRAINT ck_session_events_name_not_empty
        CHECK (length(trim(name)) > 0),

    CONSTRAINT ck_session_events_sequence_non_negative
        CHECK (sequence >= 0),

    CONSTRAINT ck_session_events_ended_after_started
        CHECK (ended_at IS NULL OR ended_at >= started_at),

    CONSTRAINT ck_session_events_duration_non_negative
        CHECK (duration_ms IS NULL OR duration_ms >= 0),

    CONSTRAINT ck_session_events_attempt_positive
        CHECK (attempt IS NULL OR attempt >= 1),

    CONSTRAINT ck_session_events_tokens_non_negative
        CHECK ((tokens_in IS NULL OR tokens_in >= 0) AND (tokens_out IS NULL OR tokens_out >= 0)),

    CONSTRAINT ck_session_events_cost_non_negative
        CHECK (cost_paise IS NULL OR cost_paise >= 0),

    -- Payload size cap, enforced in the database as well as the application.
    -- The application redacts and truncates; this is the backstop that keeps a
    -- bug from writing an unbounded LLM response into every trace row.
    CONSTRAINT ck_session_events_request_within_cap
        CHECK (request_jsonb IS NULL OR pg_column_size(request_jsonb) <= 4096),

    CONSTRAINT ck_session_events_response_within_cap
        CHECK (response_jsonb IS NULL OR pg_column_size(response_jsonb) <= 4096),

    CONSTRAINT ck_session_events_condition_inputs_is_object
        CHECK (condition_inputs_jsonb IS NULL OR jsonb_typeof(condition_inputs_jsonb) = 'object'),

    CONSTRAINT ck_session_events_branches_available_is_array
        CHECK (branches_available IS NULL OR jsonb_typeof(branches_available) = 'array')
);

-- Rendering the tree for one session in the staff console.
CREATE INDEX idx_session_events_session_sequence
    ON session_events (session_id, parent_id NULLS FIRST, sequence, started_at);

-- Walking children of a span.
CREATE INDEX idx_session_events_parent
    ON session_events (parent_id)
    WHERE parent_id IS NOT NULL;

-- "Show me everything that failed" across sessions.
CREATE INDEX idx_session_events_failures
    ON session_events (started_at DESC)
    WHERE outcome IN ('FAILED', 'TIMEOUT');

-- The 30-day payload-stripping sweep claims rows through this.
CREATE INDEX idx_session_events_payload_retention
    ON session_events (started_at)
    WHERE payloads_stripped_at IS NULL
      AND (request_jsonb IS NOT NULL OR response_jsonb IS NOT NULL);

-- Cost roll-ups per session and per LLM target.
CREATE INDEX idx_session_events_cost_by_target
    ON session_events (target, started_at)
    WHERE cost_paise IS NOT NULL;
