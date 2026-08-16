package com.interviewengine.audit.domain;

/**
 * Origin of an audit event — stored in {@code audit_logs.source}.
 *
 * <p>DB CHECK constraint: {@code source IN ('API', 'SYSTEM', 'WORKER', 'ADMIN')}.
 *
 * <ul>
 *   <li>{@link #API}    — event triggered by an authenticated HTTP request</li>
 *   <li>{@link #SYSTEM} — event generated internally (e.g. webhook processing)</li>
 *   <li>{@link #WORKER} — event from a scheduled background job</li>
 *   <li>{@link #ADMIN}  — event from an admin tool or manual intervention</li>
 * </ul>
 */
public enum AuditSource {
    API,
    SYSTEM,
    WORKER,
    ADMIN
}
