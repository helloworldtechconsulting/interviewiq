ALTER TABLE candidates
    ADD COLUMN phone VARCHAR(30);

ALTER TABLE candidates
    ADD CONSTRAINT ck_candidates_phone_not_empty
        CHECK (phone IS NULL OR length(trim(phone)) > 0);
