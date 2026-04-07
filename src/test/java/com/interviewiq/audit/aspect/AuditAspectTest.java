package com.interviewiq.audit.aspect;

import com.interviewiq.audit.annotation.Auditable;
import com.interviewiq.audit.service.AuditService;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuditAspect} — verifies entity ID resolution strategies
 * and fire-and-forget resilience (aspect failures must never surface to callers).
 */
@ExtendWith(MockitoExtension.class)
class AuditAspectTest {

    @Mock AuditService auditService;
    @Mock JoinPoint joinPoint;

    @InjectMocks AuditAspect auditAspect;

    @BeforeEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // =========================================================================
    // Entity ID resolution — strategy 1: explicit arg index
    // =========================================================================

    @Test
    void resolveEntityId_fromArgument_whenEntityIdArgIsSet() throws Exception {
        UUID entityId = UUID.randomUUID();
        Auditable ann = buildAnnotation("SESSION_CREATED", "InterviewSession", 0);
        when(joinPoint.getArgs()).thenReturn(new Object[]{entityId, "extra"});

        auditAspect.afterReturning(joinPoint, ann, null);

        ArgumentCaptor<UUID> captor = ArgumentCaptor.forClass(UUID.class);
        verify(auditService).record(isNull(), isNull(), eq("SESSION_CREATED"),
                eq("InterviewSession"), captor.capture(), any());
        assertThat(captor.getValue()).isEqualTo(entityId);
    }

    // =========================================================================
    // Entity ID resolution — strategy 2a: record id() accessor
    // =========================================================================

    @Test
    void resolveEntityId_fromRecord_idAccessor() throws Exception {
        UUID entityId  = UUID.randomUUID();
        Auditable ann  = buildAnnotation("SESSION_CREATED", "InterviewSession", -1);

        // Minimal record-like return value with id() accessor
        record SessionResponse(UUID id, String status) {}
        SessionResponse result = new SessionResponse(entityId, "INVITED");

        auditAspect.afterReturning(joinPoint, ann, result);

        ArgumentCaptor<UUID> captor = ArgumentCaptor.forClass(UUID.class);
        verify(auditService).record(isNull(), isNull(), any(), any(), captor.capture(), any());
        assertThat(captor.getValue()).isEqualTo(entityId);
    }

    // =========================================================================
    // Entity ID resolution — strategy 2b: bean getId() accessor
    // =========================================================================

    @Test
    void resolveEntityId_fromBean_getIdAccessor() throws Exception {
        UUID entityId  = UUID.randomUUID();
        Auditable ann  = buildAnnotation("JOB_CREATED", "JobOpening", -1);

        // POJO-style return value with getId() accessor
        class JobResponse {
            public UUID getId() { return entityId; }
        }

        auditAspect.afterReturning(joinPoint, ann, new JobResponse());

        ArgumentCaptor<UUID> captor = ArgumentCaptor.forClass(UUID.class);
        verify(auditService).record(isNull(), isNull(), any(), any(), captor.capture(), any());
        assertThat(captor.getValue()).isEqualTo(entityId);
    }

    // =========================================================================
    // Entity ID resolution — no ID available
    // =========================================================================

    @Test
    void resolveEntityId_returnsNull_whenNoIdResolves() throws Exception {
        Auditable ann = buildAnnotation("CANDIDATE_INVITED", "Candidate", -1);

        // Return value has no id() or getId()
        auditAspect.afterReturning(joinPoint, ann, "just a string");

        ArgumentCaptor<UUID> captor = ArgumentCaptor.forClass(UUID.class);
        verify(auditService).record(isNull(), isNull(), any(), any(), captor.capture(), any());
        assertThat(captor.getValue()).isNull();
    }

    // =========================================================================
    // Fire-and-forget — exceptions must never escape the aspect
    // =========================================================================

    @Test
    void exceptionInAuditService_doesNotPropagateToCallerContext() {
        Auditable ann = buildAnnotation("SESSION_CANCELLED", "InterviewSession", -1);
        doThrow(new RuntimeException("DB down")).when(auditService).record(any(), any(), any(), any(), any(), any());

        // afterReturning must swallow the exception — the caller's transaction is unaffected
        assertThatCode(() -> auditAspect.afterReturning(joinPoint, ann, null))
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Helper — build a synthetic @Auditable with the given values
    // =========================================================================

    private Auditable buildAnnotation(String action, String entityType, int entityIdArg) {
        return new Auditable() {
            @Override public Class<Auditable> annotationType() { return Auditable.class; }
            @Override public String action()       { return action; }
            @Override public String entityType()   { return entityType; }
            @Override public int entityIdArg()     { return entityIdArg; }
        };
    }
}
