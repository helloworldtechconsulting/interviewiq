-- =============================================================================
-- V013__storage_create_storage_objects_table.sql
-- depends on: V001__company_create_companies_table.sql
-- =============================================================================

CREATE TABLE storage_objects (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    company_id   UUID         NOT NULL,
    object_key   VARCHAR(512) NOT NULL,
    bucket       VARCHAR(100) NOT NULL,
    content_type VARCHAR(100),
    size_bytes   BIGINT,

    -- tracks the business purpose of the file; drives access control and lifecycle policy
    object_type  VARCHAR(50),

    -- links this file to the specific entity that owns it (e.g. a candidate's resume,
    -- a job opening's JD). Enables queries like "find the resume for candidate X"
    -- without coupling the query to the S3 key stored on the owning entity.
    -- UUID type without a FK because entity_id can point to different tables
    -- depending on object_type (candidates, job_openings, interview_sessions, etc.).
    -- Nullable: some files (e.g. bulk exports) have no single owning entity.
    entity_id    UUID,

    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_storage_objects
        PRIMARY KEY (id),

    -- RESTRICT: storage_objects is a tombstone table — metadata rows must survive
    -- company deletion for audit and S3 cleanup reconciliation purposes.
    -- The async S3 cleanup job reads these rows AFTER the company is marked deleted;
    -- CASCADE would destroy them before the cleanup job can process them, leaving
    -- orphaned S3 objects with no tracking record.
    CONSTRAINT fk_storage_objects_companies
        FOREIGN KEY (company_id) REFERENCES companies (id) ON DELETE RESTRICT,

    -- (bucket, object_key) is the globally unique address of a file in S3;
    -- prevents duplicate metadata rows for the same physical object
    CONSTRAINT uq_storage_objects_key
        UNIQUE (bucket, object_key),

    -- size is nullable for objects whose size is not yet known (e.g. multipart
    -- upload in progress); once set it must be within a sane range.
    -- upper bound: 1 TiB (1099511627776 bytes) — well above any expected file size;
    -- guards against garbage values like 999999999999999999 from buggy clients
    CONSTRAINT ck_storage_objects_size_valid
        CHECK (size_bytes IS NULL OR size_bytes BETWEEN 0 AND 1099511627776),

    CONSTRAINT ck_storage_objects_object_type_valid
        CHECK (object_type IS NULL OR object_type IN (
            'RESUME',
            'JOB_DESCRIPTION',
            'TRANSCRIPT',
            'EVALUATION_EXPORT'
        )),

    -- object_key must not be an empty string — an empty key is not a valid S3 address
    CONSTRAINT ck_storage_objects_key_not_empty
        CHECK (length(trim(object_key)) > 0),

    -- bucket must not be an empty string
    CONSTRAINT ck_storage_objects_bucket_not_empty
        CHECK (length(trim(bucket)) > 0)
);
