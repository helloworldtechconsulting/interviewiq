-- =============================================================================
-- V003__auth_create_refresh_tokens_table.sql
-- depends on: V002__auth_create_users_table.sql
-- =============================================================================

CREATE TABLE refresh_tokens (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    token_hash  VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_refresh_tokens
        PRIMARY KEY (id),

    CONSTRAINT fk_refresh_tokens_users
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,

    CONSTRAINT uq_refresh_tokens_token_hash
        UNIQUE (token_hash),

    -- expiry must be strictly in the future relative to row creation
    CONSTRAINT ck_refresh_tokens_future_expiry
        CHECK (expires_at > created_at)
);
