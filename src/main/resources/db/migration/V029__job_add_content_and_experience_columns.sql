ALTER TABLE job_openings
    ADD COLUMN description      TEXT,
    ADD COLUMN experience_min   INTEGER,
    ADD COLUMN experience_max   INTEGER,
    ADD COLUMN questions_jsonb  JSONB;

ALTER TABLE job_openings
    ADD CONSTRAINT ck_job_openings_experience_min_non_negative
        CHECK (experience_min IS NULL OR experience_min >= 0),
    ADD CONSTRAINT ck_job_openings_experience_max_non_negative
        CHECK (experience_max IS NULL OR experience_max >= 0),
    ADD CONSTRAINT ck_job_openings_experience_range_valid
        CHECK (
            experience_min IS NULL
            OR experience_max IS NULL
            OR experience_max >= experience_min
        );

ALTER TABLE job_openings
    ADD CONSTRAINT ck_job_openings_questions_jsonb_is_array
        CHECK (
            questions_jsonb IS NULL
            OR jsonb_typeof(questions_jsonb) = 'array'
        );
