-- =============================================================================
-- V029__job_add_content_and_experience_columns.sql
-- purpose: add missing job opening content columns that the application layer
--          reads/writes but the database does not yet store.
--
-- COLUMNS ADDED TO job_openings:
--
--   description     — rich text job description displayed on the job detail page
--                     and passed to the AI question-generation prompt alongside
--                     jd_text. NULL for jobs with only an uploaded JD file.
--
--   experience_min  — minimum years of experience required (INTEGER). Used to filter
--                     candidates and tune question difficulty in the AI prompt.
--
--   experience_max  — maximum years of experience preferred (INTEGER). NULL = no upper bound.
--                     Both values are 0-based (0 = fresh graduate accepted).
--                     INTEGER chosen to match Java Integer → Hibernate int4 mapping.
--
--   questions_jsonb — AI-generated question bank derived from jd_text + description.
--                     This is the GENERIC pool for the job opening. The interview
--                     session's questions_json (V007) holds the PERSONALISED subset
--                     derived from this bank + the specific candidate's resume_text.
--                     Pipeline:
--                       JD upload → JdExtractionWorker → jd_text populated
--                       → AI call → questions_jsonb populated on job_openings
--                       → session created → AI call → questions_json on session
--                     Structure: array of {id, text, type, dimension, difficulty,
--                                          followUpEnabled} — same as session-level
--
-- JSONB structure (questions_jsonb):
--   [
--     {
--       "id":              "q_001",
--       "text":            "Walk me through a distributed system you designed.",
--       "type":            "TECHNICAL",
--       "dimension":       "technical",
--       "difficulty":      "MEDIUM",
--       "followUpEnabled": true
--     }
--   ]
-- =============================================================================


-- ── job_openings additions ────────────────────────────────────────────────────

ALTER TABLE job_openings
    ADD COLUMN description      TEXT,
    ADD COLUMN experience_min   INTEGER,
    ADD COLUMN experience_max   INTEGER,
    ADD COLUMN questions_jsonb  JSONB;


-- Experience range invariants
-- INTEGER range is 0–2,147,483,647; the CHECK constraints below guard against
-- garbage values without ever blocking a real use case
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


-- questions_jsonb structural guard: must be an array when present.
-- Mirrors ck_interview_sessions_questions_json_is_array (V024) — prevents
-- the AI pipeline from writing a bare object or string into the question bank.
ALTER TABLE job_openings
    ADD CONSTRAINT ck_job_openings_questions_jsonb_is_array
        CHECK (
            questions_jsonb IS NULL
            OR jsonb_typeof(questions_jsonb) = 'array'
        );
