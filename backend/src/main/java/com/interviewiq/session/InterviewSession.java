package com.interviewiq.session;

import com.interviewiq.common.BaseEntity;
import com.vladmihalcea.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "interview_sessions", indexes = {
        @Index(name = "idx_sessions_candidate", columnList = "candidate_id"),
        @Index(name = "idx_sessions_company", columnList = "company_id"),
        @Index(name = "idx_sessions_status", columnList = "status"),
        @Index(name = "idx_sessions_token", columnList = "invite_token"),
        @Index(name = "idx_sessions_job", columnList = "job_opening_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewSession extends BaseEntity {

    @Column(nullable = false)
    private UUID candidateId;

    @Column(nullable = false)
    private UUID jobOpeningId;

    @Column(nullable = false)
    private UUID companyId;

    private UUID availabilitySlotId;

    @Column(nullable = false, unique = true)
    private String inviteToken;

    @Column(nullable = false)
    private LocalDateTime inviteExpiresAt;

    private LocalDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status;

    private LocalDateTime startedAt;

    private LocalDateTime endedAt;

    private Integer durationSeconds;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> questions;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> transcript;

    private String recordingGcsPath;

    private BigDecimal overallScore;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, BigDecimal> dimensionScores;

    @Column(columnDefinition = "TEXT")
    private String evaluationSummary;

    private String recommendation;

    @Column(columnDefinition = "TEXT")
    private String employerNotes;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> antiCheatFlags;
}
