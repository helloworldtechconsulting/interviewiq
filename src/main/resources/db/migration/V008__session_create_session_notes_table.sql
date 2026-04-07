-- =============================================================================
-- V008__session_create_session_notes_table.sql
-- depends on: V001__company_create_companies_table.sql
--             V007__session_create_interview_sessions_table.sql
-- =============================================================================

CREATE TABLE session_notes (
    id         UUID        NOT NULL DEFAULT gen_random_uuid(),
    company_id UUID        NOT NULL,
    session_id UUID        NOT NULL,
    note_type  VARCHAR(50) NOT NULL,
    content    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_session_notes
        PRIMARY KEY (id),

    -- composite FK: guarantees the referenced session belongs to the same company.
    -- CASCADE: notes are owned by the session — no session, no notes
    -- requires uq_interview_sessions_company_id_id on interview_sessions(company_id, id) — added in V007
    CONSTRAINT fk_session_notes_sessions
        FOREIGN KEY (company_id, session_id) REFERENCES interview_sessions (company_id, id) ON DELETE CASCADE,

    CONSTRAINT ck_session_notes_type_valid
        CHECK (note_type IN ('SYSTEM', 'INTERVIEWER', 'AI'))
);
