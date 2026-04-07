package com.interviewiq.audit.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method as an auditable operation.
 *
 * <p>When applied, {@link com.interviewiq.audit.aspect.AuditAspect} intercepts
 * the method after successful return and writes a structured row to the
 * {@code audit_logs} table. The aspect is fire-and-forget (async) so it does
 * not affect the latency or transaction of the annotated method.
 *
 * <h2>Usage</h2>
 * <pre>
 * // Entity ID extracted from the return value's id() accessor:
 * {@literal @}Auditable(action = "SESSION_CREATED", entityType = "SESSION")
 * public SessionResponse create(CreateSessionRequest req) { ... }
 *
 * // Entity ID taken from method argument at index 0 (a UUID):
 * {@literal @}Auditable(action = "SESSION_CANCELLED", entityType = "SESSION", entityIdArg = 0)
 * public SessionResponse cancel(UUID sessionId) { ... }
 *
 * // Void method — entity ID from arg 0, no return value to inspect:
 * {@literal @}Auditable(action = "JOB_DELETED", entityType = "JOB", entityIdArg = 0)
 * public void delete(UUID jobId) { ... }
 * </pre>
 *
 * <h2>Entity ID resolution</h2>
 * <ol>
 *   <li>If {@link #entityIdArg()} ≥ 0, the argument at that index is used
 *       (must be a {@code UUID}).</li>
 *   <li>Otherwise the return value is inspected: first via {@code id()} (Java
 *       record accessor), then via {@code getId()} (bean getter convention).</li>
 *   <li>If neither yields a UUID, {@code entity_id} is stored as null (valid
 *       per the DB schema — both columns must be null or both non-null).</li>
 * </ol>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Auditable {

    /**
     * The audit action name stored in {@code audit_logs.action}.
     * Use SCREAMING_SNAKE_CASE, e.g. {@code "SESSION_CREATED"}.
     * Maximum 100 characters (DB constraint).
     */
    String action();

    /**
     * The entity type stored in {@code audit_logs.entity_type}.
     * Use SCREAMING_SNAKE_CASE, e.g. {@code "SESSION"}.
     * Leave blank to store {@code null} in the DB.
     * Maximum 100 characters (DB constraint).
     */
    String entityType() default "";

    /**
     * 0-based index of the method argument that holds the entity {@code UUID}.
     * Set to {@code -1} (default) to instruct the aspect to extract the entity
     * ID from the return value instead.
     */
    int entityIdArg() default -1;
}
