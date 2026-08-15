-- =============================================================================
-- V044 — Employer custom question bank
--
-- PRD v2.1 §7.5.8. Optional, and the default remains 100% AI-generated. The
-- employer uploads questions against a job by CSV or by pasting them one per
-- line; they are tagged EMPLOYER, always included, and occupy the CORE segment
-- first so that every candidate for that job is asked them and stays comparable.
-- The AI then fills the remainder to reach the tier's question count.
--
-- TWO RULES THAT ARE NOT NEGOTIABLE, both encoded here:
--
--   1. Employer questions still pass the prohibited-topic safety filter. If a
--      customer uploads "Are you planning to have children?", we refuse it and
--      tell them why. Hence safety_status and rejection_reason: a question is
--      never usable until it has been screened, and the refusal names the
--      prohibited category so the employer can correct it.
--
--   2. Employer questions bypass the QUALITY critic but never the SAFETY filter.
--      If an employer wants to ask something the quality critic would score
--      poorly, that is their call. Safety is not their call, and there is no
--      override — which is why there is no "approved_by_override" column and no
--      way to move a REJECTED row to APPROVED other than editing the text.
-- =============================================================================

CREATE TABLE employer_questions (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    company_id     UUID         NOT NULL,
    job_opening_id UUID         NOT NULL,

    question_text  TEXT         NOT NULL,

    -- PENDING   → uploaded, safety filter has not yet run
    -- APPROVED  → cleared the prohibited-topic filter; eligible for the core segment
    -- REJECTED  → refused; rejection_reason names the prohibited category
    safety_status  VARCHAR(20)  NOT NULL DEFAULT 'PENDING',

    -- Populated only on REJECTED. Names the category (e.g. "marital status")
    -- rather than a generic refusal, so the employer can fix the question.
    rejection_reason TEXT,

    -- Employer-controlled ordering within the core segment. If an employer
    -- supplies more questions than the tier holds, the extras rotate across
    -- candidates in this order (§7.5.8).
    display_order  INTEGER      NOT NULL DEFAULT 0,

    created_by     UUID,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_employer_questions
        PRIMARY KEY (id),

    CONSTRAINT fk_employer_questions_job_openings
        FOREIGN KEY (company_id, job_opening_id)
            REFERENCES job_openings (company_id, id) ON DELETE CASCADE,

    CONSTRAINT fk_employer_questions_users
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,

    CONSTRAINT ck_employer_questions_text_not_empty
        CHECK (length(trim(question_text)) > 0),

    CONSTRAINT ck_employer_questions_safety_status_valid
        CHECK (safety_status IN ('PENDING', 'APPROVED', 'REJECTED')),

    -- A rejection without a stated reason is useless to the employer.
    CONSTRAINT ck_employer_questions_rejection_reason_required
        CHECK (
            (safety_status = 'REJECTED' AND rejection_reason IS NOT NULL
                                        AND length(trim(rejection_reason)) > 0)
            OR (safety_status <> 'REJECTED' AND rejection_reason IS NULL)
        ),

    CONSTRAINT ck_employer_questions_display_order_non_negative
        CHECK (display_order >= 0)
);

-- Question-bank assembly reads the approved set for a job in display order.
CREATE INDEX idx_employer_questions_job_order
    ON employer_questions (job_opening_id, display_order, created_at);

-- The safety filter claims unscreened rows.
CREATE INDEX idx_employer_questions_pending_safety
    ON employer_questions (created_at)
    WHERE safety_status = 'PENDING';
