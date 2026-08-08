ALTER TABLE interview_sessions
    ADD COLUMN room_token_hash        VARCHAR(255),
    ADD COLUMN room_token_expires_at  TIMESTAMPTZ,
    ADD COLUMN duration_seconds       INTEGER,
    ADD COLUMN recording_s3_key       VARCHAR(512),
    ADD COLUMN proctoring_flags_jsonb JSONB,
    ADD COLUMN cancelled_at           TIMESTAMPTZ;

ALTER TABLE interview_sessions
    ADD CONSTRAINT uq_interview_sessions_room_token_hash
        UNIQUE (room_token_hash);

ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_room_token_consistency
        CHECK (
            (room_token_hash IS NULL     AND room_token_expires_at IS NULL)
            OR
            (room_token_hash IS NOT NULL AND room_token_expires_at IS NOT NULL)
        );

ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_room_token_future_expiry
        CHECK (room_token_expires_at IS NULL OR room_token_expires_at > created_at);

ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_duration_positive
        CHECK (duration_seconds IS NULL OR duration_seconds > 0);

ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_duration_requires_timestamps
        CHECK (
            duration_seconds IS NULL
            OR (started_at IS NOT NULL AND ended_at IS NOT NULL)
        );

ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_proctoring_flags_is_array
        CHECK (
            proctoring_flags_jsonb IS NULL
            OR jsonb_typeof(proctoring_flags_jsonb) = 'array'
        );

ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_cancelled_at_status_consistency
        CHECK (
            cancelled_at IS NULL
            OR status = 'CANCELLED'
        );

ALTER TABLE interview_sessions
    ADD CONSTRAINT ck_interview_sessions_cancelled_at_after_created
        CHECK (cancelled_at IS NULL OR cancelled_at >= created_at);
