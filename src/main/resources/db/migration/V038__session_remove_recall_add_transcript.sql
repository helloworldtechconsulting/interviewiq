-- V038: Remove Recall.ai columns from interview_sessions.
--
-- Recall.ai is being replaced by an in-browser WebRTC interview room.
-- The recall_bot_id and google_meet_url columns are no longer written by
-- any application code and are safe to drop. Existing NULL values in
-- production will be lost, which is acceptable: live Recall.ai sessions
-- were never in production.
--
-- The transcript is now inlined into questionsJson as an "answer" field
-- on each question object, so no separate transcript column is required.

ALTER TABLE interview_sessions
    DROP COLUMN IF EXISTS recall_bot_id,
    DROP COLUMN IF EXISTS google_meet_url;
