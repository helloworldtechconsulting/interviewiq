-- =============================================================================
-- 01-init.sql — Postgres bootstrap
--
-- Runs once, the first time the postgres container's data volume is created.
-- (Postgres skips /docker-entrypoint-initdb.d on subsequent boots.)
--
-- What this does:
--   1. Creates an additional `interviewiq_test` database alongside the primary
--      `interviewiq_dev` database, so integration tests run against an
--      isolated DB without trampling on dev seed data.
--   2. Grants the application user full privileges on both databases.
--   3. Enables common extensions (uuid-ossp, pgcrypto) on both DBs to mirror
--      what Flyway expects.
--
-- The application's `interviewiq_dev` (or whatever DB_NAME is set to) is
-- created automatically by the official postgres image via POSTGRES_DB and
-- POSTGRES_USER — DO NOT recreate it here.
-- =============================================================================

-- Extensions on the primary DB (POSTGRES_DB)
\connect :"POSTGRES_DB" :"POSTGRES_USER"
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Reconnect as superuser to create the additional DB
\connect postgres postgres

DO
$$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_database WHERE datname = 'interviewiq_test') THEN
      CREATE DATABASE interviewiq_test;
   END IF;
END
$$;

-- Grant the app role full access to the test DB.
DO
$$
DECLARE
   app_user TEXT := current_setting('custom.app_user', true);
BEGIN
   IF app_user IS NULL OR app_user = '' THEN
      app_user := 'interviewiq';
   END IF;
   EXECUTE format('GRANT ALL PRIVILEGES ON DATABASE interviewiq_test TO %I', app_user);
EXCEPTION
   WHEN undefined_object THEN
      RAISE NOTICE 'Role % does not exist; skipping grant. (Postgres image will create it from POSTGRES_USER.)', app_user;
END
$$;

-- Extensions on the test DB
\connect interviewiq_test
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pgcrypto;
