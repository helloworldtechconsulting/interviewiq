package com.interviewengine.scheduling.infrastructure;

import com.interviewengine.scheduling.domain.CapacityBucket;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CapacityBucketRepository extends JpaRepository<CapacityBucket, UUID> {

    Optional<CapacityBucket> findByBucketStart(OffsetDateTime bucketStart);

    /**
     * Loads a contiguous run of buckets <strong>under a row lock</strong>, in a
     * deterministic order, for the booking path.
     *
     * <p>Two things matter here and both are deliberate.
     *
     * <p><strong>The lock.</strong> PRD §7.9 requires that bucket reservation use
     * the same row-lock discipline as the work queues: "two candidates booking
     * the last slot in a bucket, and two settlements racing on the same
     * promotional balance, are the same class of bug as duplicate evaluation."
     * Checking room and then taking it must happen inside one lock, or two
     * bookings both see room and both take it.
     *
     * <p><strong>The ordering.</strong> {@code ORDER BY bucket_start} is not
     * cosmetic — it is deadlock avoidance. A Comprehensive booking locks twelve
     * rows; if two overlapping bookings acquired them in different orders they
     * would deadlock. Always ascending by time means any two overlapping
     * bookings acquire their shared rows in the same sequence.
     *
     * <p>Note this deliberately does <em>not</em> use {@code SKIP LOCKED}: a
     * contended bucket must be waited for and re-read, not skipped. Skipping
     * would silently treat a locked bucket as absent, and an absent bucket means
     * empty — which is exactly the over-booking this guards against.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
           SELECT b FROM CapacityBucket b
           WHERE b.bucketStart >= :from AND b.bucketStart < :until
           ORDER BY b.bucketStart ASC
           """)
    List<CapacityBucket> lockRange(@Param("from") OffsetDateTime from,
                                   @Param("until") OffsetDateTime until);

    /** Unlocked read for the available-times query, which only needs a snapshot. */
    @Query("""
           SELECT b FROM CapacityBucket b
           WHERE b.bucketStart >= :from AND b.bucketStart < :until
           ORDER BY b.bucketStart ASC
           """)
    List<CapacityBucket> findRange(@Param("from") OffsetDateTime from,
                                   @Param("until") OffsetDateTime until);

    List<CapacityBucket> findAllByIdIn(List<UUID> ids);

    /**
     * Saturation monitoring (§8, metrics and alarms). A rising ratio here is the
     * signal to raise the capacity bar or the pod ceiling before candidates
     * start seeing "no time available".
     */
    @Query("""
           SELECT COUNT(b) FROM CapacityBucket b
           WHERE b.bucketStart >= :from AND b.bucketStart < :until
             AND b.occupiedCount >= b.capacity
           """)
    long countSaturatedInRange(@Param("from") OffsetDateTime from,
                               @Param("until") OffsetDateTime until);
}
