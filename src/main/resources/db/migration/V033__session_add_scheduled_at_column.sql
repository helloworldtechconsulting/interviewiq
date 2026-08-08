ALTER TABLE interview_sessions
    ADD COLUMN scheduled_at TIMESTAMPTZ;

ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_scheduled_at_after_created
        CHECK (scheduled_at IS NULL OR scheduled_at > created_at);

CREATE INDEX idx_interview_sessions_company_scheduled_at
    ON interview_sessions (company_id, scheduled_at ASC)
    WHERE status IN ('INVITED', 'STARTED');
