package com.interviewengine.scheduling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A five-minute unit of platform occupancy (PRD v2.1 §7.4.2).
 *
 * <p>This entity replaces {@code AvailabilitySlot}. Employer-published
 * availability windows are deleted entirely in v2.1 — there is no window
 * management UI, no employer slot administration, and capacity is the
 * <em>only</em> constraint on when a candidate may interview. A booking occupies
 * every bucket its interview spans, sized by the job's duration tier, and a time
 * is offered to the candidate if and only if every bucket it spans has room.
 *
 * <p><strong>Availability is genuinely 24×7.</strong> The AI does not keep office
 * hours, and a candidate who wants to interview at 11pm on a Sunday should be
 * able to. That is a selling point, not an oversight. Quiet hours are
 * deliberately not built: if a customer later objects to a 3am interview, a
 * per-company setting is a small addition, and the PRD is explicit that it should
 * not be built speculatively.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Two candidates booking the last slot in a bucket is the obvious failure
 * mode, and §7.9 requires it be handled by the same row-lock discipline as the
 * work queues rather than by optimistic retry in the UI. Three defences apply
 * together: the reservation path takes a row lock, {@link #version} is an
 * optimistic-lock second line, and a database CHECK makes
 * {@code occupiedCount > capacity} unrepresentable.
 *
 * <p>Rows are created lazily on first booking. An absent row is an empty bucket —
 * a 30-day 24×7 horizon would be 8,640 rows, most never touched.
 */
@Entity
@Table(name = "capacity_buckets")
public class CapacityBucket {

    /** Width of one bucket. */
    public static final Duration WIDTH = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** Always UTC, always aligned to a five-minute boundary. */
    @Column(nullable = false, unique = true, updatable = false)
    private OffsetDateTime bucketStart;

    /**
     * The concurrency bar for this bucket. Stored per row rather than read from
     * configuration at query time, so that raising the platform-wide bar does
     * not retroactively change the capacity that existing bookings were
     * accepted against.
     */
    @Column(nullable = false)
    private int capacity;

    @Column(nullable = false)
    private int occupiedCount = 0;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /**
     * Truncates an instant down to the bucket that contains it.
     *
     * <p>The database rejects a misaligned {@code bucketStart}, because
     * overlapping buckets would each track occupancy separately and jointly
     * over-book the platform. This is the only supported way to derive one.
     */
    public static OffsetDateTime align(OffsetDateTime instant) {
        OffsetDateTime utc = instant.withOffsetSameInstant(ZoneOffset.UTC);
        long minutes = utc.getMinute();
        return utc.truncatedTo(ChronoUnit.HOURS)
                  .plusMinutes(minutes - (minutes % WIDTH.toMinutes()));
    }

    public boolean hasRoom() {
        return occupiedCount < capacity;
    }

    public int remainingCapacity() {
        return Math.max(0, capacity - occupiedCount);
    }

    /**
     * Takes one unit of capacity.
     *
     * @throws IllegalStateException if the bucket is already full — the caller
     *         must have checked under the same row lock, so reaching this is a
     *         bug rather than an expected race outcome
     */
    public void occupy() {
        if (!hasRoom()) {
            throw new IllegalStateException(
                    "Capacity bucket " + bucketStart + " is full (" + occupiedCount + "/" + capacity + ")");
        }
        occupiedCount++;
    }

    /**
     * Returns one unit of capacity when a session is cancelled, expires or is
     * rescheduled. Floors at zero: a double release is a bug, but leaving the
     * count negative would permanently inflate apparent availability.
     */
    public void release() {
        if (occupiedCount > 0) {
            occupiedCount--;
        }
    }

    // ── Accessors ───────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public OffsetDateTime getBucketStart() { return bucketStart; }
    public void setBucketStart(OffsetDateTime bucketStart) { this.bucketStart = bucketStart; }

    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }

    public int getOccupiedCount() { return occupiedCount; }
    public void setOccupiedCount(int occupiedCount) { this.occupiedCount = occupiedCount; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "CapacityBucket{start=" + bucketStart + ", occupied=" + occupiedCount + "/" + capacity + "}";
    }
}
