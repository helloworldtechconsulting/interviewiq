package com.interviewengine.storage.domain;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Metadata tombstone for a file stored in S3.
 *
 * <p>No {@code updatedAt}: rows are append-only after creation. The S3 cleanup
 * job reads these rows to reconcile orphaned objects — CASCADE deletes on company
 * deletion would destroy tracking records before the cleanup job processes them.
 *
 * <p>{@code entityId} has no FK: it can reference different tables depending on
 * {@code objectType} (candidates, job_openings, interview_sessions, etc.).
 *
 * <p>DB table: {@code storage_objects} (V013)
 */
@Entity
@Table(name = "storage_objects")
public class StorageObject {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** FK → companies(id). ON DELETE RESTRICT for cleanup reconciliation. */
    @Column(nullable = false, updatable = false)
    private UUID companyId;

    /** S3 object key within the bucket. Unique per (bucket, object_key). */
    @Column(nullable = false, length = 512)
    private String objectKey;

    @Column(nullable = false, length = 100)
    private String bucket;

    @Column(length = 100)
    private String contentType;

    /** File size in bytes. Nullable for in-progress multipart uploads. */
    private Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private StorageObjectType objectType;

    /**
     * ID of the owning entity (candidate, job opening, session, etc.).
     * No FK constraint — entity_id can reference different tables.
     * Nullable for bulk exports with no single owning entity.
     */
    private UUID entityId;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }

    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public StorageObjectType getObjectType() { return objectType; }
    public void setObjectType(StorageObjectType objectType) { this.objectType = objectType; }

    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "StorageObject{id=" + id + ", objectKey='" + objectKey + "', objectType=" + objectType + "}";
    }
}
