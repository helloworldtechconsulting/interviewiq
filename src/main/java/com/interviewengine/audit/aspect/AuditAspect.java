package com.interviewengine.audit.aspect;

import com.interviewengine.audit.annotation.Auditable;
import com.interviewengine.audit.domain.AuditSource;
import com.interviewengine.audit.service.AuditService;
import com.interviewengine.shared.security.EmployerPrincipal;
import com.interviewengine.shared.security.SecurityContext;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * AOP aspect that intercepts methods annotated with {@link Auditable} and
 * writes a record to {@code audit_logs} after each successful invocation.
 *
 * <h2>Pointcut</h2>
 * <p>{@code @AfterReturning} fires only when the target method returns normally.
 * Exceptions do not trigger an audit entry — a rolled-back operation should not
 * appear in the audit trail as a completed event.
 *
 * <h2>Security context</h2>
 * <p>The caller identity ({@code company_id}, {@code user_id}) is extracted from
 * the Spring Security context. If no employer principal is present (e.g. the
 * call originates from a scheduled worker) both IDs are stored as null.
 *
 * <h2>Entity ID resolution</h2>
 * <ol>
 *   <li>If {@link Auditable#entityIdArg()} ≥ 0, the argument at that index is used
 *       (must be a {@code UUID}).</li>
 *   <li>Otherwise the return value is inspected via reflection: first {@code id()}
 *       (Java record accessor), then {@code getId()} (bean getter convention).</li>
 *   <li>If neither yields a UUID, {@code entity_id} is stored as null.</li>
 * </ol>
 *
 * <h2>Fire-and-forget</h2>
 * <p>The aspect delegates to {@link AuditService#record} which is {@code @Async}
 * and runs in its own transaction. Audit failures are silently swallowed — they
 * must never cause the caller's operation to fail.
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditService auditService;

    public AuditAspect(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Intercepts any method annotated with {@link Auditable} after it returns
     * successfully. Builds the audit record and delegates to {@link AuditService}.
     *
     * @param jp        the join point (provides method arguments)
     * @param auditable the annotation instance (provides action, entityType, entityIdArg)
     * @param result    the method's return value (null for void methods)
     */
    @AfterReturning(pointcut = "@annotation(auditable)", returning = "result")
    public void afterReturning(JoinPoint jp, Auditable auditable, Object result) {
        try {
            UUID companyId = null;
            UUID userId    = null;

            EmployerPrincipal principal = SecurityContext.employerOrNull();
            if (principal != null) {
                companyId = principal.companyId();
                userId    = principal.userId();
            }

            UUID   entityId   = resolveEntityId(jp, auditable, result);
            String entityType = auditable.entityType().isBlank() ? null : auditable.entityType();

            auditService.record(companyId, userId, auditable.action(),
                    entityType, entityId, AuditSource.API);

        } catch (Exception e) {
            // Never let aspect logic break the caller.
            log.error("AuditAspect: unexpected error building audit record for action={}: {}",
                    auditable.action(), e.getMessage(), e);
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Resolves the entity UUID using the strategy described in the class Javadoc.
     */
    private UUID resolveEntityId(JoinPoint jp, Auditable auditable, Object result) {
        // Strategy 1: explicit argument index
        int argIdx = auditable.entityIdArg();
        if (argIdx >= 0) {
            Object[] args = jp.getArgs();
            if (argIdx < args.length && args[argIdx] instanceof UUID id) {
                return id;
            }
        }

        // Strategy 2: extract from return value via id() or getId()
        if (result != null) {
            for (String methodName : new String[]{"id", "getId"}) {
                try {
                    Object value = result.getClass().getMethod(methodName).invoke(result);
                    if (value instanceof UUID id) {
                        return id;
                    }
                } catch (NoSuchMethodException ignored) {
                    // method not present — try next candidate
                } catch (Exception e) {
                    log.debug("AuditAspect: could not invoke {} on {}: {}",
                            methodName, result.getClass().getSimpleName(), e.getMessage());
                }
            }
        }

        return null;
    }
}
