-- =============================================================================
-- V021__indexes_create_gin_indexes.sql
-- purpose: GIN indexes for JSONB columns — enables containment (@>),
--          key-existence (?), and jsonpath (@@) queries
--
-- OPERATOR CLASS SELECTION:
--   jsonb_path_ops — supports @> and @@ (jsonpath) only; ~20-30% smaller index
--                    than jsonb_ops; optimal when access is containment-only
--   jsonb_ops      — supports @>, <@, ?, ?|, ?& (full operator set); required
--                    when key-existence probes (?) are part of the query pattern
--
-- INTENTIONALLY OMITTED (per migration plan Section 3.3):
--   interview_sessions.questions_json — written once, read once, never filtered;
--                                       no query benefits from a GIN index here
--   audit_logs.changes               — high-write append-only table; GIN
--                                      maintenance cost exceeds query benefit
--                                      in Phase 1; add if compliance queries demand
-- =============================================================================


-- -----------------------------------------------------------------------------
-- evaluation_reports — evaluation_json
-- -----------------------------------------------------------------------------

-- Purpose: find reports by embedded skill tags, per-dimension scores, or
-- structured AI-generated competency assessments contained within the document.
--   WHERE evaluation_json @> '{"dimension": "TECHNICAL"}'
--   WHERE evaluation_json @@ '$.overall_band == "STRONG_HIRE"'
--
-- jsonb_path_ops: access pattern is containment-only. Scalar queryable fields
-- (overall_score, recommendation) are typed columns with B-tree indexes already.
-- Key-existence probes (?) are not needed — jsonb_path_ops is the leaner choice.
--
-- partial: NULL evaluation_json rows (AI pipeline not yet complete) carry no
-- searchable content. Excluding them eliminates write-amplification on the
-- frequent "insert pending report" path.
CREATE INDEX idx_evaluation_reports_evaluation_json
    ON evaluation_reports USING GIN (evaluation_json jsonb_path_ops)
    WHERE evaluation_json IS NOT NULL;


-- -----------------------------------------------------------------------------
-- webhook_events — payload_json
-- -----------------------------------------------------------------------------

-- Purpose: debug and replay queries — find events where the provider payload
-- contains a specific correlation field or error code:
--   WHERE payload_json @> '{"bot_id": "recall_bot_xyz"}'
--   WHERE payload_json ? 'error_code'
--   WHERE payload_json @> '{"event": "payment.captured"}'
--
-- jsonb_ops: key-existence checks (? operator) are essential for debugging
-- tooling — it probes for the presence of provider-specific fields before
-- safely extracting their values. jsonb_path_ops would not support ? queries.
--
-- NOT partial: payload_json is NOT NULL on webhook_events (enforced in V012
-- and further guaranteed by the CHECK constraint added in V024). Every row
-- is indexable — no filtering benefit from a partial predicate.
CREATE INDEX idx_webhook_events_payload_json
    ON webhook_events USING GIN (payload_json jsonb_ops);
