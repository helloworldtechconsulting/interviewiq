-- =============================================================================
-- V020__core_add_query_indexes.sql
-- purpose: production query performance indexes across the InterviewEngine schema
--
-- SKIPPED INDEXES (already created by UNIQUE constraints):
--   evaluation_reports.session_id    → uq_evaluation_reports_session
--   webhook_events(provider,         → uq_webhook_events_provider_idempotency_key
--     idempotency_key)                 covers provider as leading column
--   email_events.provider_message_id → uq_email_events_provider_message_id
--   wallets.company_id               → uq_wallets_company
--   candidates(company_id, id)       → uq_candidates_company_id_id covers
--                                      company_id prefix queries
--
-- CORRECTION vs spec:
--   (entity_type, entity_id) was listed under storage_objects in the spec,
--   but those columns do not exist on storage_objects — they belong to
--   audit_logs. Index created on audit_logs where the columns actually exist.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- job_openings
-- -----------------------------------------------------------------------------

-- dashboard: list open/archived jobs per company filtered by status
CREATE INDEX idx_job_openings_company_status
    ON job_openings (company_id, status);

-- dashboard: paginate jobs per company ordered by newest first
CREATE INDEX idx_job_openings_company_created_at
    ON job_openings (company_id, created_at DESC);


-- -----------------------------------------------------------------------------
-- candidates
-- -----------------------------------------------------------------------------

-- candidate list per job opening ordered by application date
CREATE INDEX idx_candidates_job_opening_created_at
    ON candidates (job_opening_id, created_at DESC);

-- NOTE: a dedicated (company_id) index is intentionally omitted.
-- uq_candidates_company_id_id UNIQUE(company_id, id) already covers
-- WHERE company_id = ? queries via its leading column — a second
-- single-column index would be redundant write overhead.


-- -----------------------------------------------------------------------------
-- interview_sessions
-- -----------------------------------------------------------------------------

-- dashboard: filter sessions by company and status (e.g. all STARTED sessions)
CREATE INDEX idx_interview_sessions_company_status
    ON interview_sessions (company_id, status);

-- look up all sessions for a given candidate (candidate profile page)
CREATE INDEX idx_interview_sessions_candidate_id
    ON interview_sessions (candidate_id);

-- Recall bot webhook correlation: find a session by its bot ID
-- partial: recall_bot_id is NULL for sessions where the bot hasn't joined yet
CREATE INDEX idx_interview_sessions_recall_bot_id
    ON interview_sessions (recall_bot_id)
    WHERE recall_bot_id IS NOT NULL;

-- scheduled job: find INVITED sessions whose invite link has expired
-- partial: only INVITED rows are relevant — completed/failed sessions are excluded
CREATE INDEX idx_interview_sessions_invite_expiry
    ON interview_sessions (status, invite_expires_at)
    WHERE status = 'INVITED';

-- dashboard: paginate sessions per company ordered by newest first
CREATE INDEX idx_interview_sessions_company_created_at
    ON interview_sessions (company_id, created_at DESC);


-- -----------------------------------------------------------------------------
-- session_notes
-- -----------------------------------------------------------------------------

-- fetch all notes for a given session (session detail page)
CREATE INDEX idx_session_notes_session_id
    ON session_notes (session_id);


-- -----------------------------------------------------------------------------
-- evaluation_reports
-- -----------------------------------------------------------------------------

-- SKIPPED: (session_id) index already exists via:
--   uq_evaluation_reports_session UNIQUE(session_id)


-- -----------------------------------------------------------------------------
-- wallet_transactions
-- -----------------------------------------------------------------------------

-- billing history: paginate transactions per wallet ordered by newest first
CREATE INDEX idx_wallet_transactions_wallet_created_at
    ON wallet_transactions (wallet_id, created_at DESC);

-- reconciliation: find the transaction linked to a specific session
-- partial: most transactions have no session (top-ups, manual credits)
CREATE INDEX idx_wallet_transactions_session_id
    ON wallet_transactions (session_id)
    WHERE session_id IS NOT NULL;


-- -----------------------------------------------------------------------------
-- webhook_events
-- -----------------------------------------------------------------------------

-- worker queue: fetch unprocessed events ordered by arrival time
-- partial: processed = TRUE rows (the vast majority over time) are excluded,
-- keeping the index small and the worker scan fast.
-- 'processed' is omitted from the key columns — the predicate already fixes
-- it to FALSE, so including it in the key would be a no-op overhead
CREATE INDEX idx_webhook_events_unprocessed
    ON webhook_events (created_at)
    WHERE processed = FALSE;

-- analytics / debugging: filter events by provider and type
CREATE INDEX idx_webhook_events_provider_event_type
    ON webhook_events (provider, event_type);


-- -----------------------------------------------------------------------------
-- storage_objects
-- -----------------------------------------------------------------------------

-- fetch all files owned by a company (file manager, cleanup job)
CREATE INDEX idx_storage_objects_company_id
    ON storage_objects (company_id);


-- -----------------------------------------------------------------------------
-- audit_logs
-- -----------------------------------------------------------------------------

-- audit trail: paginate log entries per company ordered by newest first
CREATE INDEX idx_audit_logs_company_created_at
    ON audit_logs (company_id, created_at DESC);

-- security investigation: find all audit events touching a specific entity
-- (e.g. "show me everything that happened to candidate X")
-- partial: entity_type IS NULL rows are system-level events with no subject —
-- excluded to keep the index focused on entity-scoped lookups
CREATE INDEX idx_audit_logs_entity
    ON audit_logs (entity_type, entity_id)
    WHERE entity_type IS NOT NULL AND entity_id IS NOT NULL;


-- -----------------------------------------------------------------------------
-- email_events
-- -----------------------------------------------------------------------------

-- look up delivery history for a specific recipient email address, ordered by newest first.
-- composite index covers the common query pattern:
--   WHERE recipient_email = ? ORDER BY created_at DESC
-- a single-column (recipient_email) index would require a separate sort step
CREATE INDEX idx_email_events_recipient_email
    ON email_events (recipient_email, created_at DESC);

-- monitoring / retry jobs: filter emails by delivery status and date range
CREATE INDEX idx_email_events_status_created_at
    ON email_events (status, created_at);
