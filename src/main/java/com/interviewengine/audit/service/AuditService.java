package com.interviewengine.audit.service;

import com.interviewengine.audit.domain.AuditLog;
import com.interviewengine.audit.domain.AuditSource;
import com.interviewengine.audit.infrastructure.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes audit records to the {@code audit_logs} table.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li>{@code @Async} — audit writes run on a separate virtual thread so they
 *       never add latency to the caller's request/transaction.</li>
 *   <li>{@code @Transactional(propagation = REQUIRES_NEW)} — audit records are
 *       committed in their own transaction. If the caller's transaction rolls
 *       back the audit record is still persisted (forensic integrity). If the
 *       audit write itself fails, the caller is unaffected.</li>
 *   <li>Errors are caught and logged — a failed audit write must never cause a
 *       user-visible error.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>Because this method is {@code @Async}, Spring AOP proxies the call and
 * executes it on a thread from the application's async executor (virtual
 * threads, configured via {@code spring.threads.virtual.enabled=true}). The
 * {@link com.interviewengine.audit.aspect.AuditAspect} must call this service as a
 * Spring-managed bean (not via {@code this}) so the async proxy is honoured.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Asynchronously records an audit event.
     *
     * <p>Runs in a new transaction — independent of the caller's transaction.
     * Swallows all exceptions to guarantee the caller is never affected by an
     * audit write failure.
     *
     * @param companyId  caller's company UUID (null for system/worker events)
     * @param userId     acting user UUID (null for system/worker events)
     * @param action     action name, e.g. {@code "SESSION_CREATED"}
     * @param entityType logical entity type, e.g. {@code "SESSION"} (null allowed)
     * @param entityId   affected entity UUID (null when entityType is null)
     * @param source     origin of the event ({@link AuditSource})
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID companyId,
                       UUID userId,
                       String action,
                       String entityType,
                       UUID entityId,
                       AuditSource source) {
        try {
            // Enforce DB constraint: entity_type and entity_id must be both null or both non-null.
            String resolvedType = (entityType != null && !entityType.isBlank()) ? entityType : null;
            UUID   resolvedId   = (resolvedType != null) ? entityId : null;

            AuditLog entry = AuditLog.of(companyId, userId, action, resolvedType, resolvedId, source);
            auditLogRepository.save(entry);

            log.debug("Audit: action={} entityType={} entityId={} companyId={} userId={}",
                    action, resolvedType, resolvedId, companyId, userId);

        } catch (Exception e) {
            // Audit failures must never surface to callers — log and continue.
            log.error("Failed to persist audit record: action={} entityType={} entityId={} — {}",
                    action, entityType, entityId, e.getMessage(), e);
        }
    }
}
