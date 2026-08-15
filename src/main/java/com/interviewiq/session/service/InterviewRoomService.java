package com.interviewiq.session.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.job.domain.DurationTier;
import com.interviewiq.session.domain.InterviewSession;
import com.interviewiq.session.domain.ProctoringEvent;
import com.interviewiq.session.domain.ProctoringEventType;
import com.interviewiq.session.domain.QuestionSource;
import com.interviewiq.session.domain.SessionAnswer;
import com.interviewiq.session.domain.SessionStatus;
import com.interviewiq.session.infrastructure.InterviewSessionRepository;
import com.interviewiq.session.infrastructure.ProctoringEventRepository;
import com.interviewiq.session.infrastructure.SessionAnswerRepository;
import com.interviewiq.session.room.RoomEvent;
import com.interviewiq.session.room.RoomMessenger;
import com.interviewiq.session.room.RoomSessionRegistry;
import com.interviewiq.shared.exception.ResourceNotFoundException;
import com.interviewiq.shared.exception.SessionStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Drives one interview from the server side (PRD v2.1 §7.5.2, §7.5.7).
 *
 * <p>The division of labour is the whole design. The browser speaks the question,
 * transcribes the answer, records the video and uploads it to object storage. This
 * service pushes questions, persists answers, runs the timer and moves the session
 * through its states. It never touches media.
 *
 * <h2>Every answer is persisted as it arrives</h2>
 *
 * <p>Not batched at the end. §7.5.7: "If a candidate drops off mid-interview,
 * every answered question is already persisted. If more than 50% of questions were
 * answered, a partial evaluation is generated and clearly marked Incomplete."
 * §17 rates candidate drop-off as HIGH probability, so this is the expected case
 * rather than the exceptional one.
 */
@Service
public class InterviewRoomService {

    private static final Logger log = LoggerFactory.getLogger(InterviewRoomService.class);

    /** Minutes-remaining marks at which {@code timer.warning} is pushed (§7.5.2). */
    static final List<Integer> TIMER_WARNING_MINUTES = List.of(10, 5, 1);

    /** Above this fraction of questions answered, a partial evaluation is worth generating (§7.5.7). */
    static final double PARTIAL_EVALUATION_THRESHOLD = 0.5;

    private final InterviewSessionRepository sessionRepository;
    private final SessionAnswerRepository answerRepository;
    private final ProctoringEventRepository proctoringRepository;
    private final RoomSessionRegistry registry;
    private final SessionCompletionService completionService;
    private final ObjectMapper objectMapper;

    public InterviewRoomService(InterviewSessionRepository sessionRepository,
                                SessionAnswerRepository answerRepository,
                                ProctoringEventRepository proctoringRepository,
                                RoomSessionRegistry registry,
                                SessionCompletionService completionService,
                                ObjectMapper objectMapper) {
        this.sessionRepository    = sessionRepository;
        this.answerRepository     = answerRepository;
        this.proctoringRepository = proctoringRepository;
        this.registry             = registry;
        this.completionService    = completionService;
        this.objectMapper         = objectMapper;
    }

    // =========================================================================
    // session.start
    // =========================================================================

    /**
     * Starts the interview and pushes the first question.
     *
     * <p>Idempotent: a reconnect that re-sends {@code session.start} resumes
     * rather than restarting, because restarting would reset the hard timer and
     * hand the candidate extra time.
     */
    @Transactional
    public void start(UUID sessionId, WebSocketSession socket) {
        InterviewSession session = requireSessionForUpdate(sessionId);

        if (session.getStatus() == SessionStatus.IN_PROGRESS) {
            resume(sessionId, socket);
            return;
        }
        if (session.getStatus() != SessionStatus.INVITED
                && session.getStatus() != SessionStatus.SCHEDULED) {
            throw new SessionStateException(
                    "This interview cannot be started (status: " + session.getStatus() + ").");
        }
        if (!session.areQuestionsReady()) {
            // The readiness gate should have prevented the candidate reaching
            // here, but the socket is a separate entry point and must enforce it
            // independently.
            send(socket, RoomEvent.ERROR, Map.of(
                    "message", "Your interview is still being prepared. Please wait a moment."));
            return;
        }

        session.setStatus(SessionStatus.IN_PROGRESS);
        session.setStartedAt(OffsetDateTime.now(ZoneOffset.UTC));
        sessionRepository.save(session);

        log.info("Interview started: sessionId={} tier={} hardTimer={}min",
                sessionId, session.getDurationTier(), session.getDurationTier().getMinutes());

        pushQuestionAt(session, socket, 0);
    }

    // =========================================================================
    // session.resume — reconnect after socket loss (§7.5.2)
    // =========================================================================

    /**
     * Replays current state after the browser reconnects.
     *
     * <p>"On socket loss the browser reconnects with the same session JWT and the
     * backend replays current question state." A dropped socket is expected —
     * wifi, a laptop lid, a pod rollout — and must not cost the interview.
     */
    @Transactional(readOnly = true)
    public void resume(UUID sessionId, WebSocketSession socket) {
        InterviewSession session = requireSession(sessionId);

        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            send(socket, RoomEvent.ERROR, Map.of(
                    "message", "This interview is no longer in progress."));
            return;
        }

        int nextIndex = (int) answerRepository.countBySessionId(sessionId);
        log.info("Interview resumed: sessionId={} resumingAtQuestion={}", sessionId, nextIndex);

        pushQuestionAt(session, socket, nextIndex);
    }

    // =========================================================================
    // answer.submit
    // =========================================================================

    /**
     * Persists one answer and pushes whatever comes next.
     *
     * <p>The write happens before the push, deliberately: if the push fails the
     * answer is still saved and a reconnect replays from the right place, whereas
     * pushing first would risk losing an answer to a dropped socket.
     */
    @Transactional
    public void submitAnswer(UUID sessionId,
                             WebSocketSession socket,
                             int questionIndex,
                             String transcriptText,
                             int durationSeconds) {

        InterviewSession session = requireSession(sessionId);
        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            send(socket, RoomEvent.ERROR, Map.of("message", "This interview is not in progress."));
            return;
        }

        List<JsonNode> questions = parseQuestions(session);
        if (questionIndex < 0 || questionIndex >= questions.size()) {
            send(socket, RoomEvent.ERROR, Map.of("message", "Unknown question."));
            return;
        }

        JsonNode question = questions.get(questionIndex);
        String questionText = question.path("text").asText("");

        // Idempotent on (session, index, isFollowUp): a WebSocket retry after a
        // dropped ack updates the existing row rather than creating a second.
        SessionAnswer answer = answerRepository
                .findBySessionIdAndQuestionIndexAndFollowUp(sessionId, questionIndex, false)
                .orElseGet(SessionAnswer::new);

        answer.setCompanyId(session.getCompanyId());
        answer.setSessionId(sessionId);
        answer.setQuestionIndex(questionIndex);
        answer.setQuestionText(questionText);
        answer.setQuestionSource(sourceOf(question));
        answer.setFollowUp(false);
        answer.setDurationSeconds(Math.max(0, durationSeconds));

        String transcript = transcriptText == null ? "" : transcriptText.strip();
        if (transcript.isBlank()) {
            // 90 seconds of continuous silence marks the question Skipped and
            // moves on (§7.5.7). A skipped question carries no transcript.
            answer.setSkipped(true);
            answer.setTranscriptText(null);
        } else {
            answer.setSkipped(false);
            answer.setTranscriptText(transcript);
        }
        answerRepository.save(answer);

        send(socket, RoomEvent.ACK, Map.of("questionIndex", questionIndex));

        int nextIndex = questionIndex + 1;
        if (nextIndex >= questions.size()) {
            // The bank is exhausted. The room sends session.end next; the server
            // does not end unilaterally, because the candidate may still be
            // finishing their last thought.
            send(socket, RoomEvent.QUESTION_NEXT, Map.of(
                    "index", nextIndex,
                    "text", "",
                    "isFollowUp", false,
                    "bankExhausted", true));
            return;
        }
        pushQuestionAt(session, socket, nextIndex);
    }

    // =========================================================================
    // proctoring.event
    // =========================================================================

    /**
     * Records a proctoring signal (§7.5.4).
     *
     * <p>Unknown types are dropped rather than rejected. MVP proctoring is
     * {@code tab_switch} and {@code camera_off} only, and a browser sending
     * something else should not interrupt an interview over it — these are
     * informational signals that never auto-fail a candidate.
     */
    @Transactional
    public void recordProctoringEvent(UUID sessionId, String type, String occurredAtIso) {
        ProctoringEventType eventType = parseProctoringType(type);
        if (eventType == null) {
            log.debug("Ignoring unrecognised proctoring event '{}' on session {}", type, sessionId);
            return;
        }

        InterviewSession session = requireSession(sessionId);

        ProctoringEvent event = new ProctoringEvent();
        event.setCompanyId(session.getCompanyId());
        event.setSessionId(sessionId);
        event.setEventType(eventType);
        event.setOccurredAt(parseTimestamp(occurredAtIso));
        proctoringRepository.save(event);

        log.debug("Proctoring event recorded: sessionId={} type={}", sessionId, eventType);
    }

    // =========================================================================
    // session.end
    // =========================================================================

    /**
     * Ends the interview and hands off to evaluation.
     *
     * <p>The candidate sees a completion confirmation and leaves; evaluation runs
     * offline (§7.5.5). Completion triggers it immediately rather than waiting for
     * the next poll tick.
     */
    @Transactional
    public void end(UUID sessionId, WebSocketSession socket) {
        completionService.completeInterview(sessionId, false);
        send(socket, RoomEvent.SESSION_TERMINATED, Map.of(
                "reason", "completed",
                "message", "Your interview is complete. The hiring team will be in touch."));
        registry.unregister(sessionId);
    }

    /**
     * Ends the interview because the hard timer fired.
     *
     * <p>The partial transcript is still evaluated and clearly flagged as
     * incomplete (§7.5.4, §7.5.7) — a candidate who ran out of time has still
     * given the recruiter signal.
     */
    @Transactional
    public void terminateOnTimer(UUID sessionId) {
        completionService.completeInterview(sessionId, true);

        registry.socketFor(sessionId).ifPresent(socket ->
                send(socket, RoomEvent.SESSION_TERMINATED, Map.of(
                        "reason", "time_limit",
                        "message", "Time is up. Your interview has been submitted.")));
        registry.unregister(sessionId);
    }

    // =========================================================================
    // Timer warnings
    // =========================================================================

    /**
     * Pushes {@code timer.warning} at 10, 5 and 1 minute before the cutoff, and
     * terminates when the tier's hard limit is reached.
     *
     * <p>Driven by a scheduled sweep rather than per-session timers: a
     * {@code ScheduledFuture} per interview would need cancelling on every exit
     * path, and a missed cancellation leaks a task that fires against a finished
     * session.
     */
    public void checkTimers(UUID sessionId, WebSocketSession socket) {
        InterviewSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || session.getStatus() != SessionStatus.IN_PROGRESS
                || session.getStartedAt() == null) {
            return;
        }

        DurationTier tier = session.getDurationTier();
        Duration elapsed = Duration.between(session.getStartedAt(), OffsetDateTime.now(ZoneOffset.UTC));
        long remainingMinutes = tier.getMinutes() - elapsed.toMinutes();

        if (remainingMinutes <= 0) {
            terminateOnTimer(sessionId);
            return;
        }
        if (TIMER_WARNING_MINUTES.contains((int) remainingMinutes)) {
            send(socket, RoomEvent.TIMER_WARNING, Map.of("minutesRemaining", remainingMinutes));
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void pushQuestionAt(InterviewSession session, WebSocketSession socket, int index) {
        List<JsonNode> questions = parseQuestions(session);
        if (index >= questions.size()) {
            send(socket, RoomEvent.QUESTION_NEXT, Map.of(
                    "index", index, "text", "", "isFollowUp", false, "bankExhausted", true));
            return;
        }

        JsonNode question = questions.get(index);
        Map<String, Object> payload = new HashMap<>();
        payload.put("index", index);
        payload.put("text", question.path("text").asText(""));
        payload.put("type", question.path("category").asText("TECHNICAL"));
        payload.put("isFollowUp", false);
        payload.put("source", sourceOf(question).name());
        payload.put("totalQuestions", questions.size());

        send(socket, RoomEvent.QUESTION_NEXT, payload);
    }

    private List<JsonNode> parseQuestions(InterviewSession session) {
        String json = session.getQuestionsJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) {
                return List.of();
            }
            return java.util.stream.StreamSupport
                    .stream(array.spliterator(), false)
                    .toList();
        } catch (Exception e) {
            log.error("Unparseable question bank on session {}", session.getId(), e);
            return List.of();
        }
    }

    private QuestionSource sourceOf(JsonNode question) {
        return "EMPLOYER".equalsIgnoreCase(question.path("source").asText(""))
                ? QuestionSource.EMPLOYER
                : QuestionSource.AI;
    }

    private ProctoringEventType parseProctoringType(String type) {
        if (type == null) {
            return null;
        }
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "tab_switch" -> ProctoringEventType.TAB_SWITCH;
            case "camera_off" -> ProctoringEventType.CAMERA_OFF;
            default -> null;
        };
    }

    private OffsetDateTime parseTimestamp(String iso) {
        if (iso == null || iso.isBlank()) {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
        try {
            return OffsetDateTime.parse(iso);
        } catch (Exception e) {
            // A browser clock we cannot parse is not worth failing over.
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    private InterviewSession requireSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));
    }

    private InterviewSession requireSessionForUpdate(UUID sessionId) {
        return sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("InterviewSession", sessionId));
    }

    private void send(WebSocketSession socket, RoomEvent event, Map<String, Object> payload) {
        RoomMessenger.send(socket, event, payload, objectMapper);
    }

    /** Exposed for the completion path: was enough answered to be worth evaluating? */
    Optional<Double> completionRatio(UUID sessionId, int totalQuestions) {
        if (totalQuestions <= 0) {
            return Optional.empty();
        }
        long answered = answerRepository.countAnsweredBySessionId(sessionId);
        return Optional.of((double) answered / totalQuestions);
    }
}
