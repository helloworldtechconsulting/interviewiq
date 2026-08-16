-- =============================================================================
-- V040 — Drop RECALL_AI from the webhook provider allow-list
--
-- V038 removed the Recall.ai columns from interview_sessions, but the
-- webhook_events CHECK constraint still admitted a 'RECALL_AI' provider and the
-- enum still carried the constant. PRD v2.1 §11 is explicit that
-- POST /api/v1/webhooks/recall does not exist in this design and that any code
-- still serving it is dead and must be removed.
--
-- Rows are converted rather than deleted: a historical Recall.ai delivery is
-- still an audit record of something that happened, and SYSTEM is the correct
-- bucket for a provider we no longer integrate with. In practice this affects
-- nothing — Recall.ai was never enabled in staging or production.
-- =============================================================================

UPDATE webhook_events
SET provider = 'SYSTEM'
WHERE provider = 'RECALL_AI';

ALTER TABLE webhook_events
    DROP CONSTRAINT IF EXISTS ck_webhook_events_provider_valid;

ALTER TABLE webhook_events
    ADD CONSTRAINT ck_webhook_events_provider_valid
        CHECK (provider IN ('RAZORPAY', 'SYSTEM'));
