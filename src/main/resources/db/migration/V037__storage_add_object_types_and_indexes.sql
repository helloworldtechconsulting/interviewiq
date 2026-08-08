ALTER TABLE storage_objects
    DROP CONSTRAINT ck_storage_objects_object_type_valid;

ALTER TABLE storage_objects
    ADD CONSTRAINT ck_storage_objects_object_type_valid
        CHECK (object_type IS NULL OR object_type IN (
            'RESUME',
            'JOB_DESCRIPTION',
            'TRANSCRIPT',
            'EVALUATION_EXPORT',
            'COMPANY_LOGO',
            'RECORDING'
        ));

CREATE INDEX idx_interview_sessions_cancelled_at
    ON interview_sessions (cancelled_at DESC)
    WHERE status = 'CANCELLED';

CREATE INDEX idx_interview_sessions_recording_s3_key
    ON interview_sessions (recording_s3_key)
    WHERE recording_s3_key IS NOT NULL;

CREATE INDEX idx_companies_size
    ON companies (size)
    WHERE size IS NOT NULL;

CREATE INDEX idx_job_openings_experience_min
    ON job_openings (experience_min)
    WHERE experience_min IS NOT NULL;

CREATE INDEX idx_job_openings_experience_max
    ON job_openings (experience_max)
    WHERE experience_max IS NOT NULL;
