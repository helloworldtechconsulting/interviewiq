ALTER TABLE interview_sessions
    DROP COLUMN IF EXISTS recall_bot_id,
    DROP COLUMN IF EXISTS google_meet_url;
