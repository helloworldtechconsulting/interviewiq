-- =============================================================================
-- V030__candidate_add_phone_column.sql
-- purpose: add phone to candidates.
--
-- ISSUE: The Java CreateCandidateRequest record and the Candidate JPA entity
-- both declare a `phone` field, but V006 never added the column to the table.
-- Result: phone values submitted by the frontend are silently discarded by
-- Hibernate (no column to write to); the field always reads back as NULL.
--
-- phone is nullable — not every recruiter collects a candidate phone number,
-- and the AI evaluation pipeline does not require it.
--
-- Format: stored as-is from the application layer. Validation (E.164, local
-- format checks) is enforced in CreateCandidateRequest @Pattern annotations —
-- not here, because phone number formats vary by country and a DB regex
-- constraint would block legitimate international numbers on format revision.
-- =============================================================================


ALTER TABLE candidates
    ADD COLUMN phone VARCHAR(30);


-- phone must carry content when present — a blank string is not a phone number
ALTER TABLE candidates
    ADD CONSTRAINT ck_candidates_phone_not_empty
        CHECK (phone IS NULL OR length(trim(phone)) > 0);
