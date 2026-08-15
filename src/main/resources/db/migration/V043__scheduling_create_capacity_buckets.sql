-- =============================================================================
-- V043 — Capacity buckets (replaces the AvailabilitySlot entity)
--
-- PRD v2.1 §7.4.2. Employer-published availability windows are deleted entirely.
-- Platform capacity is the only constraint on when a candidate may interview,
-- and it is modelled as 5-minute occupancy buckets. A booking occupies every
-- bucket its interview would span, sized by the job's duration tier — a
-- Comprehensive interview occupies twelve buckets, a Quick screen four. A time
-- is offered to the candidate if and only if every bucket it spans has room.
--
-- Availability is genuinely 24x7: there is no business-hours restriction and no
-- blackout period. Quiet hours are deliberately not built (§7.4.2) — if a
-- customer later objects to a candidate interviewing at 3am, a per-company
-- setting is a small addition. It is not built speculatively.
--
-- CONCURRENCY. Two candidates booking the last slot in a bucket is the obvious
-- failure, and §7.9 requires it be handled by the same row-lock discipline as
-- the work queues, not by optimistic retry in the UI. This table therefore
-- carries both defences the PRD asks for: reservation takes a row lock
-- (SELECT ... FOR UPDATE), and `version` provides an optimistic-lock second
-- line of defence. occupied_count is additionally floored at zero and capped at
-- capacity by CHECK, so an over-booking cannot be persisted even if application
-- logic is wrong.
--
-- Rows are created lazily, on first booking into a bucket. There is no
-- pre-seeding job: a 30-day 24x7 horizon is 8,640 buckets, and the overwhelming
-- majority would never be touched. An absent row means an empty bucket.
-- =============================================================================

CREATE TABLE capacity_buckets (
    id             UUID        NOT NULL DEFAULT gen_random_uuid(),

    -- Start of the 5-minute bucket, always UTC and always aligned to a
    -- 5-minute boundary (enforced below). The alignment check is what stops a
    -- caller inventing overlapping buckets that would each track occupancy
    -- separately and jointly over-book the platform.
    bucket_start   TIMESTAMPTZ NOT NULL,

    -- The concurrency bar for this bucket, derived from the capacity analysis.
    -- Stored per row rather than read from configuration so that raising the
    -- platform bar does not retroactively change the capacity that already-made
    -- bookings were accepted against.
    capacity       INTEGER     NOT NULL,

    occupied_count INTEGER     NOT NULL DEFAULT 0,

    -- Optimistic lock — the second line of defence named in §7.9.
    version        BIGINT      NOT NULL DEFAULT 0,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_capacity_buckets
        PRIMARY KEY (id),

    -- One row per instant. This is the constraint that makes concurrent
    -- lazy creation safe: the loser of an INSERT race gets a unique violation
    -- and retries into the winner's row.
    CONSTRAINT uq_capacity_buckets_bucket_start
        UNIQUE (bucket_start),

    CONSTRAINT ck_capacity_buckets_aligned_to_five_minutes
        CHECK (date_part('epoch', bucket_start)::BIGINT % 300 = 0),

    CONSTRAINT ck_capacity_buckets_capacity_positive
        CHECK (capacity > 0),

    CONSTRAINT ck_capacity_buckets_occupied_non_negative
        CHECK (occupied_count >= 0),

    -- Over-booking is unrepresentable, not merely discouraged.
    CONSTRAINT ck_capacity_buckets_occupied_within_capacity
        CHECK (occupied_count <= capacity),

    CONSTRAINT ck_capacity_buckets_version_non_negative
        CHECK (version >= 0)
);

-- The available-times query walks a date range looking for room, and must
-- return in under 500 ms p95 over a 30-day horizon (§8, performance).
CREATE INDEX idx_capacity_buckets_start_with_room
    ON capacity_buckets (bucket_start)
    WHERE occupied_count < capacity;

-- =============================================================================
-- Which buckets a given session occupies.
--
-- Held as an explicit join table rather than recomputed from
-- scheduled_start_at + duration_tier, because release must be exact. When a
-- session is cancelled, expires or is rescheduled, we free precisely the
-- buckets that were taken — even if the job's tier has changed in the meantime.
-- =============================================================================

CREATE TABLE session_capacity_reservations (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    company_id        UUID        NOT NULL,
    session_id        UUID        NOT NULL,
    capacity_bucket_id UUID       NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_session_capacity_reservations
        PRIMARY KEY (id),

    -- A session may not occupy the same bucket twice — which would double-count
    -- occupancy and leak capacity on release.
    CONSTRAINT uq_session_capacity_reservations_session_bucket
        UNIQUE (session_id, capacity_bucket_id),

    CONSTRAINT fk_session_capacity_reservations_sessions
        FOREIGN KEY (company_id, session_id)
            REFERENCES interview_sessions (company_id, id) ON DELETE CASCADE,

    CONSTRAINT fk_session_capacity_reservations_buckets
        FOREIGN KEY (capacity_bucket_id)
            REFERENCES capacity_buckets (id) ON DELETE RESTRICT
);

CREATE INDEX idx_session_capacity_reservations_session
    ON session_capacity_reservations (session_id);

CREATE INDEX idx_session_capacity_reservations_bucket
    ON session_capacity_reservations (capacity_bucket_id);
