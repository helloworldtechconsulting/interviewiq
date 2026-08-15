package com.interviewiq.session.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * One answer given during an interview (PRD v2.1 §10).
 *
 * <p>Answers are rows rather than entries in the session's {@code questions_json}
 * blob, because two requirements need them to be durable individually:
 *
 * <ul>
 *   <li><strong>Drop-off resilience.</strong> "If a candidate drops off
 *       mid-interview, every answered question is already persisted" (§7.5.7).
 *       Each {@code answer.submit} writes one row as it arrives.</li>
 *   <li><strong>Citable evidence.</strong> The report must carry per-question
 *       narrative evidence in which every claim cites a specific answer (§7.6).
 *       A claim needs something durable to point at.</li>
 * </ul>
 */
@Entity
@Table(name = "session_answers")
public class SessionAnswer {

    /** Below this word count the room prompts once: "Could you elaborate a little more?" (§7.5.7). */
    public static final int ELABORATION_PROMPT_WORD_THRESHOLD = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID companyId;

    @Column(nullable = false, updatable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private int questionIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String questionText;

    /**
     * Denormalised from the question bank on purpose. The report shows a source
     * label on every employer-supplied question, because a recruiter reading a
     * low Technical score needs to know whether it came from our questions or
     * theirs (§7.5.8) — and that must stay true even if the employer later edits
     * or deletes the question from the job's bank.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuestionSource questionSource = QuestionSource.AI;

    /**
     * A follow-up pushed by the followup workflow rather than drawn from the
     * pre-generated bank. Follow-ups share the index of the question they
     * follow, and are distinguished by this flag.
     */
    @Column(name = "is_follow_up", nullable = false)
    private boolean followUp = false;

    /**
     * The accumulated browser {@code SpeechRecognition} transcript. Null when the
     * question was marked skipped after 90 seconds of continuous silence.
     */
    @Column(columnDefinition = "TEXT")
    private String transcriptText;

    private Integer durationSeconds;

    /** 0–10, written by the evaluation worker. Null until the session is scored. */
    private Short score;

    @Column(nullable = false)
    private boolean skipped = false;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Word count of the transcript, used by the elaboration prompt rule (§7.5.7). */
    public int wordCount() {
        if (transcriptText == null || transcriptText.isBlank()) {
            return 0;
        }
        return transcriptText.strip().split("\\s+").length;
    }

    /** Whether this answer is substantive enough to count toward completion. */
    public boolean isAnswered() {
        return !skipped && transcriptText != null && !transcriptText.isBlank();
    }

    // ── Accessors ───────────────────────────────────────────────────────────

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCompanyId() { return companyId; }
    public void setCompanyId(UUID companyId) { this.companyId = companyId; }

    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public int getQuestionIndex() { return questionIndex; }
    public void setQuestionIndex(int questionIndex) { this.questionIndex = questionIndex; }

    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }

    public QuestionSource getQuestionSource() { return questionSource; }
    public void setQuestionSource(QuestionSource questionSource) { this.questionSource = questionSource; }

    public boolean isFollowUp() { return followUp; }
    public void setFollowUp(boolean followUp) { this.followUp = followUp; }

    public String getTranscriptText() { return transcriptText; }
    public void setTranscriptText(String transcriptText) { this.transcriptText = transcriptText; }

    public Integer getDurationSeconds() { return durationSeconds; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    public Short getScore() { return score; }
    public void setScore(Short score) { this.score = score; }

    public boolean isSkipped() { return skipped; }
    public void setSkipped(boolean skipped) { this.skipped = skipped; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "SessionAnswer{sessionId=" + sessionId + ", index=" + questionIndex
                + ", source=" + questionSource + ", followUp=" + followUp + "}";
    }
}
