-- =============================================================================
-- V032__session_add_lifecycle_columns.sql
-- purpose: add session lifecycle tracking columns to interview_sessions.
--
-- COLUMNS ADDED:
--
--   room_token_hash       — SHA-256 hash of the ephemeral WebSocket room token.
--                           Issued when the session transitions to STARTED;
--                           used to authenticate the candidate WebSocket connection
--                           during the live interview.  Like invite_token_hash,
--                           only the hash is persisted — the raw token travels
--                           only in-memory to the candidate.
--
--   room_token_expires_at — Expiry timestamp for the room token.
--                           Set to (STARTED transition time + session duration + 15 min
--                           grace). After this point the WebSocket connection is
--                           rejected and the session transitions to EXPIRED or ERROR.
--
--   duration_seconds      — ACTUAL interview duration in seconds, computed from
--                           (ended_at − started_at) at session-end time and stored
--                           explicitly. Avoids recomputing the interval on every
--                           dashboard query and enables direct ORDER BY / WHERE
--                           filtering without expression evaluation overhead.
--
--   recording_s3_key      — S3 object key for the video recording (WebM).
--                           NULL until the Recall.ai bot uploads the recording.
--                           S3 lifecycle rule: auto-deleted after 7 days
--                           (GDPR data minimisation — recording retention policy).
--
--   proctoring_flags_jsonb — Denormalised anti-cheat summary written by the
--                            evaluation pipeline after the session ends.
--                            The raw events live in session_events (V035);
--                            this JSONB is a rollup for fast employer UI display
--                            without a JOIN to session_events.
--                            NULL while in INVITED/STARTED state.
--                            Structure: see JSONB note below.
--
--   cancelled_at          — Timestamp set when the session transitions to CANCELLED.
--                           Separate from updated_at (which changes on any mutation)
--                           and ended_at (which records the natural interview end).
--                           Required for: "sessions cancelled this week" queries
--                           and refund eligibility windows (cancel within X hours
--                           of scheduled time → partial refund).
--
-- JSONB structure (proctoring_flags_jsonb):
--   [
--     { "type": "TAB_SWITCH",         "count": 2,  "firstOccurrence": "2026-04-13T10:22:11Z" },
--     { "type": "MULTI_FACE_DETECTED", "count": 1,  "firstOccurrence": "2026-04-13T10:35:44Z" },
--     { "type": "CAMERA_OFF",          "totalSeconds": 12 }
--   ]
-- =============================================================================


ALTER TABLE interview_sessions
    ADD COLUMN room_token_hash        VARCHAR(255),
    ADD COLUMN room_token_expires_at  TIMESTAMPTZ,
    ADD COLUMN duration_seconds       INTEGER,
    ADD COLUMN recording_s3_key       VARCHAR(512),
    ADD COLUMN proctoring_flags_jsonb JSONB,
    ADD COLUMN cancelled_at           TIMESTAMPTZ;


-- ── room token constraints ────────────────────────────────────────────────────

-- hash must be globally unique across all sessions
ALTER TABLE interview_sessions
    ADD CONSTRAINT uq_interview_sessions_room_token_hash
        UNIQUE (room_token_hash);

-- hash and expiry must be set together or not at all:
-- a token without an expiry is an indefinitely valid credential (security hole);
-- an expiry without a token is unresolvable
ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_room_token_consistency
        CHECK (
            (room_token_hash IS NULL     AND room_token_expires_at IS NULL)
            OR
            (room_token_hash IS NOT NULL AND room_token_expires_at IS NOT NULL)
        );

-- room token must expire after the session row was created
ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_room_token_future_expiry
        CHECK (room_token_expires_at IS NULL OR room_token_expires_at > created_at);


-- ── duration_seconds constraint ───────────────────────────────────────────────

-- duration must be a positive integer when set;
-- zero-duration interviews are data-entry errors
ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_duration_positive
        CHECK (duration_seconds IS NULL OR duration_seconds > 0);

-- if duration is set, both timestamps that define it must also be set
-- (guards against partial writes where only one timestamp was committed)
ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_duration_requires_timestamps
        CHECK (
            duration_seconds IS NULL
            OR (started_at IS NOT NULL AND ended_at IS NOT NULL)
        );


-- ── proctoring_flags_jsonb structural constraint ──────────────────────────────

-- must be an array when present — mirrors the session_events table structure
ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_proctoring_flags_is_array
        CHECK (
            proctoring_flags_jsonb IS NULL
            OR jsonb_typeof(proctoring_flags_jsonb) = 'array'
        );


-- ── cancelled_at consistency constraint ──────────────────────────────────────

-- cancelled_at must only be set on CANCELLED sessions;
-- any other status with a cancelled_at timestamp indicates a state machine bug
ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_cancelled_at_status_consistency
        CHECK (
            cancelled_at IS NULL
            OR status = 'CANCELLED'
        );

-- cancelled_at cannot predate session creation
ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_cancelled_at_after_created
        CHECK (cancelled_at IS NULL OR cancelled_at >= created_at);
