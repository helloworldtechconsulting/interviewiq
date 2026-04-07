-- =============================================================================
-- V014__audit_create_audit_logs_table.sql
-- depends on: V001__company_create_companies_table.sql
--             V002__auth_create_users_table.sql
-- note: audit_logs is an append-only table — rows must never be updated or deleted.
--       immutability is enforced at the application and role-permission level;
--       a trigger-based guard can be added in a future V030+ migration if required.
-- =============================================================================

CREATE TABLE audit_logs (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),

    -- nullable: system-generated events may have no company context
    company_id  UUID,

    -- nullable: SET NULL preserves the audit record if the actor's account is deleted;
    -- losing the user row must not erase the evidence of what they did
    user_id     UUID,

    action      VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100),
    entity_id   UUID,
    details_json JSONB,

    -- identifies the origin of the event (user request, background job, admin tool, etc.)
    source      VARCHAR(50),

    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- no updated_at — audit records are write-once by design

    CONSTRAINT pk_audit_logs
        PRIMARY KEY (id),

    -- SET NULL: company deletion must not destroy audit history
    CONSTRAINT fk_audit_logs_companies
        FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE SET NULL,

    -- SET NULL: user deletion must not destroy audit history
    CONSTRAINT fk_audit_logs_users
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,

    -- action must carry meaningful content — a blank string is not an audit event
    CONSTRAINT ck_audit_logs_action_not_empty
        CHECK (length(trim(action)) > 0),

    -- entity_type must not be a blank string when present —
    -- mirrors the same guard applied to action
    CONSTRAINT ck_audit_logs_entity_type_not_empty
        CHECK (entity_type IS NULL OR length(trim(entity_type)) > 0),

    -- entity_type and entity_id must be set together or not at all:
    -- an entity ID without a type is unresolvable;
    -- a type without an ID is ambiguous
    CONSTRAINT ck_audit_logs_entity_consistency
        CHECK (
            (entity_type IS NULL AND entity_id IS NULL)
            OR
            (entity_type IS NOT NULL AND entity_id IS NOT NULL)
        ),

    CONSTRAINT ck_audit_logs_source_valid
        CHECK (source IS NULL OR source IN ('API', 'SYSTEM', 'WORKER', 'ADMIN'))
);
