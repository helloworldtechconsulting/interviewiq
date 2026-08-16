package com.interviewengine.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Append-only record of a significant business event.
 *
 * <p><strong>Immutability contract</strong>: rows in {@code audit_logs} must
 * never be updated or deleted. Hibernate's dirty-checking still works fine
 * because the {@link com.interviewengine.audit.infrastructure.AuditLogRepository}
 * only ever calls {@code save()} on freshly-created instances. No service
 * method loads and re-saves an existing {@code AuditLog}.
 *
 * <p><strong>Nullable FKs</strong>: both {@code company_id} and {@code user_id}
 * are nullable by design (see migration V014). If the actor's account or company
 * is later deleted, their FK is set to {@code SET NULL} — preserving the audit
 * record as forensic evidence.
 *
 * <p>DB table: {@code audit_logs} (V014)
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** FK → companies(id) ON DELETE SET NULL. Null for system-generated events. */
    @Column(updatable = false)
    private UUID companyId;

    /** FK → users(id) ON DELETE SET NULL. Null for system/worker events. */
    @Column(updatable = false)
    private UUID userId;

    /** Action name, e.g. {@code SESSION_CREATED}. Max 100 chars. */
    @Column(nullable = false, length = 100, updatable = false)
    private String action;

    /** Logical entity type, e.g. {@code SESSION}. Null = no specific entity. */
    @Column(length = 100, updatable = false)
    private String entityType;

    /** UUID of the affected entity. Null when {@code entityType} is null. */
    @Column(updatable = false)
    private UUID entityId;

    /** Optional structured context as a JSON string (stored as JSONB). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", updatable = false)
    private String detailsJson;

    /**
     * Event origin: API, SYSTEM, WORKER, ADMIN.
     * Must satisfy the DB CHECK constraint.
     */
    @Column(length = 50, updatable = false)
    private String source;

    /** UTC timestamp — set on INSERT, never modified. No updated_at. */
    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    // ── Factory ──────────────────────────────────────────────────────────────

    public static AuditLog of(UUID companyId,
                              UUID userId,
                              String action,
                              String entityType,
                              UUID entityId,
                              AuditSource source) {
        AuditLog log = new AuditLog();
        log.companyId  = companyId;
        log.userId     = userId;
        log.action     = action;
        log.entityType = entityType;
        log.entityId   = entityId;
        log.source     = source != null ? source.name() : null;
        return log;
    }

    // ── Getters (no setters — write-once) ────────────────────────────────────

    public UUID getId()                  { return id; }
    public UUID getCompanyId()           { return companyId; }
    public UUID getUserId()              { return userId; }
    public String getAction()            { return action; }
    public String getEntityType()        { return entityType; }
    public UUID getEntityId()            { return entityId; }
    public String getDetailsJson()       { return detailsJson; }
    public String getSource()            { return source; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
