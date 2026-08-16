package com.interviewengine.audit.infrastructure;

import com.interviewengine.audit.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Repository for the append-only {@code audit_logs} table.
 *
 * <p>Only {@code save()} (INSERT) is ever called on this repository.
 * No update or delete methods should be added — the audit trail is immutable
 * by contract (see migration V014 and {@link AuditLog} Javadoc).
 */
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {
}
