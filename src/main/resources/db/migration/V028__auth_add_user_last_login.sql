ALTER TABLE users
    ADD COLUMN last_login_at TIMESTAMPTZ;

ALTER TABLE users
    ADD CONSTRAINT ck_users_last_login_at_after_created
        CHECK (last_login_at IS NULL OR last_login_at >= created_at);

CREATE INDEX idx_users_last_login_at
    ON users (last_login_at DESC)
    WHERE last_login_at IS NOT NULL;
