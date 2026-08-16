package com.interviewengine.session.infrastructure;

import com.interviewengine.session.domain.SessionAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionAnswerRepository extends JpaRepository<SessionAnswer, UUID> {

    /** A session's answers in the order they were asked, follow-ups after their parent question. */
    List<SessionAnswer> findAllBySessionIdOrderByQuestionIndexAscFollowUpAsc(UUID sessionId);

    /**
     * Idempotency lookup for {@code answer.submit}: a WebSocket retry after a
     * dropped acknowledgement must update the existing row rather than create a
     * second one.
     */
    Optional<SessionAnswer> findBySessionIdAndQuestionIndexAndFollowUp(
            UUID sessionId, int questionIndex, boolean followUp);

    /**
     * How many questions the candidate actually answered.
     *
     * <p>Drives the partial-evaluation rule: above 50% completion a partial
     * evaluation is generated and clearly marked <em>Incomplete</em> (§7.5.7).
     */
    @Query("""
           SELECT COUNT(a) FROM SessionAnswer a
           WHERE a.sessionId = :sessionId
             AND a.skipped = false
             AND a.transcriptText IS NOT NULL
           """)
    long countAnsweredBySessionId(@Param("sessionId") UUID sessionId);

    long countBySessionId(UUID sessionId);
}
