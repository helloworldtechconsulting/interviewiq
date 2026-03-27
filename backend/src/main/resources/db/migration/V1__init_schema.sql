-- Companies table
CREATE TABLE companies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    domain VARCHAR(255),
    wallet_balance_paise BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Users (employers) table
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    role VARCHAR(20) NOT NULL DEFAULT 'ADMIN' CHECK (role IN ('ADMIN', 'INTERVIEWER', 'VIEWER')),
    google_sub VARCHAR(255) UNIQUE,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Job Openings table
CREATE TABLE job_openings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    department VARCHAR(255),
    location_type VARCHAR(20) NOT NULL DEFAULT 'REMOTE' CHECK (location_type IN ('REMOTE', 'ON_SITE', 'HYBRID')),
    employment_type VARCHAR(20) NOT NULL DEFAULT 'FULL_TIME' CHECK (employment_type IN ('FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERNSHIP')),
    description TEXT,
    jd_gcs_path VARCHAR(500),
    jd_extracted_text TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'CLOSED')),
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Candidates table
CREATE TABLE candidates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    job_opening_id UUID NOT NULL REFERENCES job_openings(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(50),
    resume_gcs_path VARCHAR(500),
    resume_extracted_text TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(job_opening_id, email)
);

-- Availability Slots table
CREATE TABLE availability_slots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_opening_id UUID NOT NULL REFERENCES job_openings(id) ON DELETE CASCADE,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    max_interviews INT NOT NULL DEFAULT 1,
    booked_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CHECK (end_time > start_time),
    CHECK (max_interviews > 0)
);

-- Interview Sessions table
CREATE TABLE interview_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    candidate_id UUID NOT NULL REFERENCES candidates(id) ON DELETE CASCADE,
    job_opening_id UUID NOT NULL REFERENCES job_openings(id) ON DELETE CASCADE,
    company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    availability_slot_id UUID REFERENCES availability_slots(id) ON DELETE SET NULL,
    invite_token VARCHAR(500) NOT NULL UNIQUE,
    invite_expires_at TIMESTAMP NOT NULL,
    scheduled_at TIMESTAMP,
    status VARCHAR(30) NOT NULL DEFAULT 'INVITED' CHECK (status IN (
        'INVITED', 'ACCEPTED', 'STARTED', 'COMPLETED', 'CANCELLED', 'NO_SHOW'
    )),
    started_at TIMESTAMP,
    ended_at TIMESTAMP,
    duration_seconds INT,
    questions JSONB,
    transcript JSONB,
    recording_gcs_path VARCHAR(500),
    overall_score DECIMAL(5,2),
    dimension_scores JSONB,
    evaluation_summary TEXT,
    recommendation VARCHAR(20) CHECK (recommendation IN ('STRONG_HIRE', 'HIRE', 'MAYBE', 'NO_HIRE', 'PENDING')),
    employer_notes TEXT,
    anti_cheat_flags JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Wallet Transactions table
CREATE TABLE wallet_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id UUID NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    session_id UUID REFERENCES interview_sessions(id) ON DELETE SET NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('CREDIT', 'DEBIT', 'REFUND', 'HOLD', 'RELEASE')),
    amount_paise BIGINT NOT NULL,
    razorpay_payment_id VARCHAR(255),
    description VARCHAR(500),
    balance_after_paise BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Create indices
CREATE INDEX idx_users_company ON users(company_id);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_google_sub ON users(google_sub);
CREATE INDEX idx_job_openings_company ON job_openings(company_id);
CREATE INDEX idx_job_openings_status ON job_openings(status);
CREATE INDEX idx_candidates_job ON candidates(job_opening_id);
CREATE INDEX idx_candidates_company ON candidates(company_id);
CREATE INDEX idx_candidates_email ON candidates(email);
CREATE INDEX idx_sessions_candidate ON interview_sessions(candidate_id);
CREATE INDEX idx_sessions_company ON interview_sessions(company_id);
CREATE INDEX idx_sessions_status ON interview_sessions(status);
CREATE INDEX idx_sessions_token ON interview_sessions(invite_token);
CREATE INDEX idx_sessions_job ON interview_sessions(job_opening_id);
CREATE INDEX idx_transactions_company ON wallet_transactions(company_id);
CREATE INDEX idx_transactions_session ON wallet_transactions(session_id);
CREATE INDEX idx_availability_job ON availability_slots(job_opening_id);

-- Add auto-update trigger for updated_at timestamp
CREATE OR REPLACE FUNCTION update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_companies_timestamp BEFORE UPDATE ON companies
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

CREATE TRIGGER update_users_timestamp BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

CREATE TRIGGER update_job_openings_timestamp BEFORE UPDATE ON job_openings
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

CREATE TRIGGER update_candidates_timestamp BEFORE UPDATE ON candidates
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

CREATE TRIGGER update_interview_sessions_timestamp BEFORE UPDATE ON interview_sessions
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();
