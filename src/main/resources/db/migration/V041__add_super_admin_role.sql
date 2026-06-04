-- V041__add_super_admin_role.sql
-- Add SUPER_ADMIN to the users role check constraint

ALTER TABLE users
DROP CONSTRAINT IF EXISTS ck_users_role;

ALTER TABLE users
    ADD CONSTRAINT ck_users_role
        CHECK (role IN ('ADMIN', 'RECRUITER', 'VIEWER', 'SUPER_ADMIN'));