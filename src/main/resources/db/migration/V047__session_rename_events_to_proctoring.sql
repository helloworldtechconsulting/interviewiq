-- =============================================================================
-- V047 — Rename session_events to proctoring_events, and narrow it to MVP scope
--
-- The existing session_events table is a proctoring-event log. PRD v2.1 §10
-- gives that concept the name ProctoringEvent, and reuses the name SessionEvent
-- for something entirely different: the nested domain event log of INTIQ-98
-- (created in V048). Renaming first keeps the two from colliding, and stops a
-- reader assuming a table named session_events is the trace log.
--
-- SCOPE NARROWING. §7.5.4 is explicit that MVP proctoring is tab_switch and
-- camera_off only. Both are a few lines of browser code and genuinely free. The
-- other event types in the old CHECK list describe capabilities this product
-- does not have:
--
--   MULTI_FACE_DETECTED — deferred to Phase 2. The browser FaceDetector API
--     sits behind Chrome's Experimental Web Platform Features flag and is not
--     enabled by default, and no candidate will turn on a Chrome flag to take an
--     interview. Real multi-face detection requires bundling a MediaPipe or
--     face-api.js model — a 2–6 MB WASM download running on-device. That is a
--     real feature with a real first-load cost, not a free one.
--
--   SCREEN_SHARE_*, AUDIO_*, CAMERA_ON, CONNECTION_* — never emitted by the
--     in-browser room, and listing them implies a monitoring capability we do
--     not have. Session lifecycle transitions belong in the V048 trace log.
--
-- Existing rows outside the MVP set are deleted rather than kept: in practice
-- none exist (the in-browser room does not yet emit proctoring events at all),
-- and retaining rows whose types the constraint forbids would block the ALTER.
--
-- These events are informational signals for the recruiter. They never
-- auto-fail a candidate (§7.5.4, and the advisory-only guarantee in §7.10).
-- =============================================================================

ALTER TABLE session_events RENAME TO proctoring_events;

ALTER TABLE proctoring_events RENAME CONSTRAINT pk_session_events TO pk_proctoring_events;
ALTER TABLE proctoring_events RENAME CONSTRAINT fk_session_events_sessions TO fk_proctoring_events_sessions;

ALTER INDEX idx_session_events_session_id_created_at RENAME TO idx_proctoring_events_session_occurred_at;
ALTER INDEX idx_session_events_session_event_type    RENAME TO idx_proctoring_events_session_event_type;

-- occurred_at is the browser-reported timestamp of the event; created_at stays
-- as the server write time. The report shows the chronological list by
-- occurred_at, which is what the candidate actually experienced.
ALTER TABLE proctoring_events
    ADD COLUMN occurred_at TIMESTAMPTZ;

UPDATE proctoring_events SET occurred_at = created_at WHERE occurred_at IS NULL;

ALTER TABLE proctoring_events
    ALTER COLUMN occurred_at SET NOT NULL;

-- Narrow to the MVP set.
DELETE FROM proctoring_events
WHERE event_type NOT IN ('TAB_SWITCH', 'CAMERA_OFF');

ALTER TABLE proctoring_events
    DROP CONSTRAINT IF EXISTS ck_session_events_event_type_valid;

ALTER TABLE proctoring_events
    ADD CONSTRAINT ck_proctoring_events_event_type_valid
        CHECK (event_type IN ('TAB_SWITCH', 'CAMERA_OFF'));

ALTER TABLE proctoring_events
    RENAME CONSTRAINT ck_session_events_metadata_is_object TO ck_proctoring_events_metadata_is_object;
