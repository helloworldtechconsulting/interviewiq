ALTER TABLE companies
    ADD COLUMN website     TEXT,
    ADD COLUMN industry    VARCHAR(100),
    ADD COLUMN logo_s3_key VARCHAR(512),
    ADD COLUMN size        VARCHAR(50),
    ADD COLUMN gst_number  VARCHAR(20);

ALTER TABLE companies
    ADD CONSTRAINT ck_companies_size_valid
        CHECK (size IS NULL OR size IN ('STARTUP', 'SMALL', 'MEDIUM', 'LARGE'));

ALTER TABLE companies
    ADD CONSTRAINT ck_companies_gst_not_empty
        CHECK (gst_number IS NULL OR length(trim(gst_number)) > 0);

ALTER TABLE companies
    ADD CONSTRAINT ck_companies_logo_s3_key_not_empty
        CHECK (logo_s3_key IS NULL OR length(trim(logo_s3_key)) > 0);

ALTER TABLE companies
    ADD CONSTRAINT ck_companies_website_not_empty
        CHECK (website IS NULL OR length(trim(website)) > 0);

CREATE INDEX idx_companies_industry
    ON companies (industry)
    WHERE industry IS NOT NULL;
