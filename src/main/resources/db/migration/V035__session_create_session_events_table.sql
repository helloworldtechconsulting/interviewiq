-- =============================================================================
-- V035__session_create_session_events_table.sql
-- purpose: create the session_events table for append-only anti-cheat
--          and session lifecycle event logging.
--
-- CONTEXT:
--   During an interview, the candidate's browser emits real-time events:
--   tab switches, camera on/off, multiple faces detected, connection drops, etc.
--   These are sent to the backend and stored here as an immutable audit log.
--
--   This is DISTINCT from session_notes (V008) which stores human-authored
--   annotations by the recruiter/AI reviewer.  session_events is raw,
--   machine-generated signal data at high frequency.
--
-- RELATIONSHIP TO proctoring_flags_jsonb (V032):
--   session_events  — raw event stream; every individual event, high-volume
--   proctoring_flags_jsonb — denormalised rollup on interview_sessions; one
--                            row per session summarising the events (written by
--                            the evaluation pipeline after session ends)
--
--   The employer UI reads proctoring_flags_jsonb for the summary card.
--   Compliance exports / investigations read session_events for the full log.
--
-- WRITE PATTERN:
--   Append-only.  No UPDATE or DELETE on this table in production.
--   High write volume during active sessions (events arrive every few seconds).
--   Consider time-based table partitioning by created_at month in Phase 2
--   when total row count exceeds 10M.
--
-- EVENT TYPES:
--   SESSION_STARTED         — candidate clicked "Start Interview"
--   SESSION_ENDED           — interview ended normally or was terminated
--   TAB_SWITCH              — browser tab visibility changed (visibilitychange event)
--   CAMERA_OFF              — video track disabled by the candidate
--   CAMERA_ON               — video track re-enabled
--   MULTI_FACE_DETECTED     — face detection model found >1 face in frame
--   AUDIO_MUTED             — microphone muted
--   AUDIO_UNMUTED           — microphone unmuted
--   SCREEN_SHARE_STARTED    — screen share track added
--   SCREEN_SHARE_STOPPED    — screen share track removed
--   CONNECTION_LOST         — WebSocket / WebRTC connection dropped
--   CONNECTION_RESTORED     — connection re-established after drop
-- =============================================================================


CREATE TABLE session_events (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    company_id  UUID         NOT NULL,
    session_id  UUID         NOT NULL,

    -- The browser-reported event type; drives proctoring analysis and scoring.
    event_type  VARCHAR(100) NOT NULL,

    -- Event-specific payload: { "faceCount": 2 }, { "durationSeconds": 12 },
    -- { "tabCount": 3 }, etc.  Optional — lifecycle events may have no payload.
    metadata    JSONB,

    -- created_at = time the event was RECEIVED by the server.
    -- If client-side timestamp is needed for drift analysis, embed it in metadata.
    -- Server-side timestamp is authoritative for ordering and replay protection.
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- No updated_at — this table is append-only by design.
    -- No UPDATE path exists in the application; any UPDATE to a session_event
    -- is a bug and must be caught in code review.

    CONSTRAINT pk_session_events
        PRIMARY KEY (id),

    -- composite FK: ensures the session belongs to the same company as the event;
    -- ON DELETE CASCADE: if the session is hard-deleted (should not happen in
    -- production but is allowed in test environments), events go with it.
    -- requires uq_interview_sessions_company_id_id on interview_sessions(company_id, id) — V007
    CONSTRAINT fk_session_events_sessions
        FOREIGN KEY (company_id, session_id)
            REFERENCES interview_sessions (company_id, id) ON DELETE CASCADE,

    CONSTRAINT ck_session_events_event_type_valid
        CHECK (event_type IN (
            'SESSION_STARTED',
            'SESSION_ENDED',
            'TAB_SWITCH',
            'CAMERA_OFF',
            'CAMERA_ON',
            'MULTI_FACE_DETECTED',
            'AUDIO_MUTED',
            'AUDIO_UNMUTED',
            'SCREEN_SHARE_STARTED',
            'SCREEN_SHARE_STOPPED',
            'CONNECTION_LOST',
            'CONNECTION_RESTORED'
        )),

    -- metadata must be a JSON object when present;
    -- arrays or scalar values indicate a client-side serialisation error
    CONSTRAINT ck_session_events_metadata_is_object
        CHECK (
            metadata IS NULL
            OR jsonb_typeof(metadata) = 'object'
        )
);


-- ── INDEXES ───────────────────────────────────────────────────────────────────

-- Primary read path: fetch all events for a session ordered by time.
-- Used by: evaluation pipeline rollup, compliance export, incident replay.
-- Composite (session_id, created_at DESC) eliminates sort on the most common query:
--   WHERE session_id = ? ORDER BY created_at DESC
CREATE INDEX idx_session_events_session_id_created_at
    ON session_events (session_id, created_at DESC);

-- Anti-cheat rollup: count events of each type for a session.
-- Required by the evaluation pipeline to build proctoring_flags_jsonb.
--   WHERE session_id = ? AND event_type = ?
CREATE INDEX idx_session_events_session_event_type
    ON session_events (session_id, event_type);
