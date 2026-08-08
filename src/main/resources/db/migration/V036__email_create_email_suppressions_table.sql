CREATE TABLE email_suppressions (
    id                       UUID         NOT NULL DEFAULT gen_random_uuid(),

    email                    VARCHAR(255) NOT NULL,

    reason                   VARCHAR(50)  NOT NULL,

    provider_notification_id VARCHAR(255),

    notes                    TEXT,

    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_email_suppressions
        PRIMARY KEY (id),

    CONSTRAINT uq_email_suppressions_email
        UNIQUE (email),

    CONSTRAINT ck_email_suppressions_reason_valid
        CHECK (reason IN ('BOUNCE', 'COMPLAINT', 'MANUAL')),

    CONSTRAINT ck_email_suppressions_email_lowercase
        CHECK (email = lower(email)),

    CONSTRAINT ck_email_suppressions_email_not_empty
        CHECK (length(trim(email)) > 0),

    CONSTRAINT ck_email_suppressions_notes_not_empty
        CHECK (notes IS NULL OR length(trim(notes)) > 0)
);
