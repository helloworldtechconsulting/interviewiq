package com.interviewengine.session.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One span in the per-session domain event log (INTIQ-98).
 *
 * <p><strong>This table is what replaces a BPM engine.</strong> Flowable, Camunda
 * and Temporal were evaluated and rejected for phase 1, and the conclusion of
 * that review was that "the value worth having from BPM was always the
 * <em>visibility</em>, and visibility is the cheap part to replicate". This is
 * that replication: one self-referencing table giving a nested, drillable trace
 * of every session.
 *
 * <p>The purpose is concrete. When a customer complains about an interview, you
 * open it and see the whole flow — timings, external calls with their payloads,
 * decision points, and exactly where it failed. And because the trace is
 * generated <em>by</em> execution, it cannot drift from the code the way a BPMN
 * diagram can.
 *
 * <h2>Nesting</h2>
 *
 * <p>{@code parentId} gives the tree; a null parent marks a top-level subflow.
 * Nesting is propagated in the application with Java 21 {@code ScopedValue} —
 * <strong>not</strong> {@code ThreadLocal}, because the application runs on
 * virtual threads (PRD §6.2), and a ThreadLocal would not follow work across
 * them.
 *
 * <h2>Retention</h2>
 *
 * <p>Payloads are redacted and capped at 4 KB per side, retained for 30 days and
 * then stripped; the span skeleton — timings, outcomes, decisions, economics — is
 * kept permanently. A trace therefore stays useful long after the payloads that
 * would make it a PII liability have gone.
 *
 * @see SpanKind
 * @see SpanOutcome
 */
@Entity
@Table(name = "session_events")
public class SessionEvent {

    /** Maximum serialised size of a request or response payload, in bytes. */
    public static final int PAYLOAD_CAP_BYTES = 4096;

    /** How long full payloads are kept before the sweep strips them. */
    public static final Duration PAYLOAD_RETENTION = Duration.ofDays(30);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID companyId;

    @Column(nullable = false, updatable = false)
    private UUID sessionId;

    /** Null marks a top-level subflow; otherwise the enclosing span. */
    @Column(updatable = false)
    private UUID parentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SpanKind spanKind;

    @Column(nullable = false, length = 200)
    private String name;

    /**
     * Ordering among siblings. Start time alone is not sufficient: spans running
     * on virtual threads routinely share a timestamp to the microsecond.
     */
    @Column(nullable = false)
    private int sequence;

    @Column(nullable = false)
    private OffsetDateTime startedAt;

    private OffsetDateTime endedAt;

    private Long durationMs;

    /** Null while the span is still open. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SpanOutcome outcome;

    // ── CALL spans ──────────────────────────────────────────────────────────

    /** e.g. {@code llm.question}, {@code llm.evaluation}, {@code s3.put}, {@code smtp.send}. */
    @Column(length = 100)
    private String target;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_jsonb", columnDefinition = "jsonb")
    private String requestJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_jsonb", columnDefinition = "jsonb")
    private String responseJson;

    private Integer httpStatus;

    @Column(length = 100)
    private String errorCode;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private Integer attempt;

    // ── DECISION spans ──────────────────────────────────────────────────────

    /** The condition evaluated, e.g. {@code "answerWordCount < 5"}. */
    @Column(columnDefinition = "TEXT")
    private String conditionExpr;

    /** Its inputs, e.g. {@code {"answerWordCount": 4}}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "condition_inputs_jsonb", columnDefinition = "jsonb")
    private String conditionInputsJson;

    @Column(length = 100)
    private String branchTaken;

    /**
     * Every branch that was available, including the ones not taken. Recording
     * only the taken branch would make the trace a log rather than an
     * explanation.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "branches_available", columnDefinition = "jsonb")
    private String branchesAvailable;

    // ── Economics ───────────────────────────────────────────────────────────

    private Integer tokensIn;

    private Integer tokensOut;

    /**
     * Cost of this call in paise. Cost per interview is a launch KPI with a
     * target under ₹20 (§16), and summing these spans is how it is measured
     * rather than estimated.
     */
    private Long costPaise;

    @Column(length = 100)
    private String correlationId;

    /** Set by the 30-day sweep, so a stripped span is distinguishable from one that never carried a payload. */
    private OffsetDateTime payloadsStrippedAt;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        if (startedAt == null) startedAt = now;
    }

    /**
     * Closes the span, stamping the end time, the elapsed duration and the
     * outcome in one step so the three cannot disagree.
     */
    public void close(SpanOutcome result) {
        this.endedAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.outcome = result;
        if (startedAt != null) {
            this.durationMs = Duration.between(startedAt, endedAt).toMillis();
        }
    }

    public boolean isOpen() {
        return outcome == null;
    }

    // ── Accessors ───────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public UUID getParentId() { return parentId; }
    public void setParentId(UUID parentId) { this.parentId = parentId; }

    public SpanKind getSpanKind() { return spanKind; }
    public void setSpanKind(SpanKind spanKind) { this.spanKind = spanKind; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getSequence() { return sequence; }
    public void setSequence(int sequence) { this.sequence = sequence; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getEndedAt() { return endedAt; }
    public void setEndedAt(OffsetDateTime endedAt) { this.endedAt = endedAt; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public SpanOutcome getOutcome() { return outcome; }
    public void setOutcome(SpanOutcome outcome) { this.outcome = outcome; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getRequestJson() { return requestJson; }
    public void setRequestJson(String requestJson) { this.requestJson = requestJson; }

    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }

    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Integer getAttempt() { return attempt; }
    public void setAttempt(Integer attempt) { this.attempt = attempt; }

    public String getConditionExpr() { return conditionExpr; }
    public void setConditionExpr(String conditionExpr) { this.conditionExpr = conditionExpr; }

    public String getConditionInputsJson() { return conditionInputsJson; }
    public void setConditionInputsJson(String conditionInputsJson) { this.conditionInputsJson = conditionInputsJson; }

    public String getBranchTaken() { return branchTaken; }
    public void setBranchTaken(String branchTaken) { this.branchTaken = branchTaken; }

    public String getBranchesAvailable() { return branchesAvailable; }
    public void setBranchesAvailable(String branchesAvailable) { this.branchesAvailable = branchesAvailable; }

    public Integer getTokensIn() { return tokensIn; }
    public void setTokensIn(Integer tokensIn) { this.tokensIn = tokensIn; }

    public Integer getTokensOut() { return tokensOut; }
    public void setTokensOut(Integer tokensOut) { this.tokensOut = tokensOut; }

    public Long getCostPaise() { return costPaise; }
    public void setCostPaise(Long costPaise) { this.costPaise = costPaise; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public OffsetDateTime getPayloadsStrippedAt() { return payloadsStrippedAt; }
    public void setPayloadsStrippedAt(OffsetDateTime payloadsStrippedAt) { this.payloadsStrippedAt = payloadsStrippedAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "SessionEvent{id=" + id + ", sessionId=" + sessionId
                + ", kind=" + spanKind + ", name=" + name + ", outcome=" + outcome + "}";
    }
}
