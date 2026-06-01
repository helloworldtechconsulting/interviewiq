-- =============================================================================
-- V027__company_add_business_profile_columns.sql
-- purpose: add business profile columns to companies that are already
--          referenced in CompanyProfileResponse and UpdateCompanyRequest
--          but absent from the companies table since V001.
--
--          Missing in production = these fields always return NULL from the DB
--          even though the application layer reads and writes them.
--
-- COLUMNS ADDED:
--   website      — company website URL; displayed on the employer profile page
--   industry     — sector label (e.g. "Technology"); used for analytics bucketing
--   logo_s3_key  — S3 object key for the company logo; CDN URL derived at runtime
--                  (avoids storing a mutable CDN URL that changes on bucket moves)
--   size         — company headcount band; drives pricing tier logic
--   gst_number   — Indian GST registration number; printed on Razorpay invoices
--                  for B2B billing compliance
-- =============================================================================


ALTER TABLE companies
    ADD COLUMN website     TEXT,
    ADD COLUMN industry    VARCHAR(100),
    ADD COLUMN logo_s3_key VARCHAR(512),
    ADD COLUMN size        VARCHAR(50),
    ADD COLUMN gst_number  VARCHAR(20);


-- ── Size: allowed headcount bands ────────────────────────────────────────────
-- Nullable: not all companies disclose their size at registration.
ALTER TABLE companies
    ADD CONSTRAINT ck_companies_size_valid
        CHECK (size IS NULL OR size IN ('STARTUP', 'SMALL', 'MEDIUM', 'LARGE'));


-- ── GST: must carry meaningful content when present ───────────────────────────
-- Full format validation (15 alphanumeric chars) is intentionally left to the
-- application layer — the format has changed historically and we do not want
-- a DB constraint to block legitimate values on a future format revision.
ALTER TABLE companies
    ADD CONSTRAINT ck_companies_gst_not_empty
        CHECK (gst_number IS NULL OR length(trim(gst_number)) > 0);


-- ── logo_s3_key: must be a non-empty string when set ─────────────────────────
ALTER TABLE companies
    ADD CONSTRAINT ck_companies_logo_s3_key_not_empty
        CHECK (logo_s3_key IS NULL OR length(trim(logo_s3_key)) > 0);


-- ── website: must be a non-empty string when set ─────────────────────────────
ALTER TABLE companies
    ADD CONSTRAINT ck_companies_website_not_empty
        CHECK (website IS NULL OR length(trim(website)) > 0);


-- ── INDEX: analytics grouping by industry ────────────────────────────────────
-- partial: NULL industry rows (unclassified companies) are excluded —
-- they never participate in industry-level aggregations
CREATE INDEX idx_companies_industry
    ON companies (industry)
    WHERE industry IS NOT NULL;
