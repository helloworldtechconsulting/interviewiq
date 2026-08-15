-- =============================================================================
-- V050 — Per-question narrative evidence on the evaluation report
--
-- PRD v2.1 §7.6. "Every evaluation report carries per-question narrative
-- evidence, and every claim cites a specific answer — never a bare score."
--
-- The reasoning is commercial, not cosmetic: a recruiter who can see *why* the
-- score is 72 will trust and act on it; a bare "72" gets ignored. The quoted
-- evidence is also the best defence if a candidate ever challenges a decision.
-- The PRD is unusually firm that this is "a hard output requirement on the
-- evaluation prompt, validated before the report is persisted — a report whose
-- narrative does not cite answers is a defect, not a stylistic preference."
--
-- Shape of evidence_jsonb:
--   {
--     "overallSummary": "...",                 3 sentences
--     "dimensions": {
--       "TECHNICAL":   { "narrative": "...", "citedAnswerIds": [...] },   2-3 sentences each
--       "COMMUNICATION": { ... }, "RELEVANCE": { ... }, "PROBLEM_SOLVING": { ... }
--     },
--     "perQuestion": [
--       { "answerId": "...", "questionIndex": 0, "narrative": "...", "questionSource": "AI|EMPLOYER" }
--     ]
--   }
--
-- The citation requirement is validated in the application before persisting,
-- because "does this narrative actually cite an answer that exists" is a
-- referential question a CHECK constraint cannot answer. What the database
-- enforces is the structural floor: if a report is DONE, it has evidence.
-- =============================================================================

ALTER TABLE evaluation_reports
    ADD COLUMN evidence_jsonb  JSONB,
    ADD COLUMN summary_text    TEXT,
    ADD COLUMN report_s3_key   VARCHAR(512),
    ADD COLUMN employer_notes  TEXT,
    ADD COLUMN generated_at    TIMESTAMPTZ,

    -- Set when the interview ended with more than 50% but fewer than all
    -- questions answered. Partial evaluations are generated and clearly marked
    -- Incomplete on the report (§7.5.7).
    ADD COLUMN is_partial      BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE evaluation_reports
    ADD CONSTRAINT ck_evaluation_reports_evidence_is_object
        CHECK (evidence_jsonb IS NULL OR jsonb_typeof(evidence_jsonb) = 'object');

-- A completed report without narrative evidence is the defect §7.6 describes.
ALTER TABLE evaluation_reports
    ADD CONSTRAINT ck_evaluation_reports_done_requires_evidence
        CHECK (generation_status <> 'DONE' OR evidence_jsonb IS NOT NULL);

ALTER TABLE evaluation_reports
    ADD CONSTRAINT ck_evaluation_reports_done_requires_generated_at
        CHECK (generation_status <> 'DONE' OR generated_at IS NOT NULL);

-- Report-ready time is a launch KPI: max 30 minutes hard, ~5 minutes soft,
-- median under 2 (§16). Measured from session end to generated_at.
CREATE INDEX idx_evaluation_reports_generated_at
    ON evaluation_reports (generated_at DESC)
    WHERE generated_at IS NOT NULL;

-- The KEDA Postgres scaler counts pending work through this to scale the worker
-- deployment on queue depth (Implementation Architecture Decisions §4).
CREATE INDEX idx_evaluation_reports_pending_queue
    ON evaluation_reports (created_at)
    WHERE generation_status = 'PENDING';
