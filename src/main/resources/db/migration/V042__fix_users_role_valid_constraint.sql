ALTER TABLE users
DROP CONSTRAINT IF EXISTS ck_users_role_valid;

ALTER TABLE users
    ADD CONSTRAINT ck_users_role_valid
        CHECK (role IN ('ADMIN', 'RECRUITER', 'VIEWER', 'SUPER_ADMIN'));