CREATE TABLE session_events (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    company_id  UUID         NOT NULL,
    session_id  UUID         NOT NULL,

    event_type  VARCHAR(100) NOT NULL,

    metadata    JSONB,

    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_session_events
        PRIMARY KEY (id),

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

    CONSTRAINT ck_session_events_metadata_is_object
        CHECK (
            metadata IS NULL
            OR jsonb_typeof(metadata) = 'object'
        )
);

CREATE INDEX idx_session_events_session_id_created_at
    ON session_events (session_id, created_at DESC);

CREATE INDEX idx_session_events_session_event_type
    ON session_events (session_id, event_type);
