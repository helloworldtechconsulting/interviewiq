package com.interviewengine.session.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewengine.session.service.InterviewRoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.UUID;

/**
 * The interview room's WebSocket endpoint (PRD v2.1 §7.5.2).
 *
 * <p>Serves {@code /ws/session/{sessionId}?token={candidateJWT}}, terminated by
 * the ingress controller with session affinity.
 *
 * <p>This handler is a text relay and nothing more. No audio, no video and no
 * speech processing passes through it — those all run in the candidate's browser,
 * and the recording goes browser-to-storage by pre-signed URL. The server's job
 * is to push questions, persist answers, and hold the timer.
 *
 * <h2>Reconnect and resume</h2>
 *
 * <p>"The WebSocket must survive a pod replacement." On socket loss the browser
 * reconnects with the same session JWT and this handler replays the current
 * question state. Combined with ingress affinity, a long termination grace period
 * and eviction protection, a deployment must never kill a live interview — §7.5.2
 * calls that "the single worst bug this product could ship".
 *
 * @see RoomEvent for the eight-event contract
 * @see RoomSessionRegistry for the documented single-pod limitation
 */
@Component
public class InterviewRoomHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(InterviewRoomHandler.class);

    /** Attribute key under which {@link RoomHandshakeInterceptor} stores the session id. */
    public static final String SESSION_ID_ATTRIBUTE = "interviewSessionId";

    private final InterviewRoomService roomService;
    private final RoomSessionRegistry registry;
    private final ObjectMapper objectMapper;

    public InterviewRoomHandler(InterviewRoomService roomService,
                                RoomSessionRegistry registry,
                                ObjectMapper objectMapper) {
        this.roomService  = roomService;
        this.registry     = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        UUID sessionId = sessionIdOf(socket);
        if (sessionId == null) {
            // The handshake interceptor rejects unauthenticated upgrades, so
            // reaching here without an id means a wiring fault rather than an
            // attack — but closing is still the only safe response.
            closeWith(socket, CloseStatus.POLICY_VIOLATION, "Unauthenticated interview socket");
            return;
        }
        registry.register(sessionId, socket);
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        UUID sessionId = sessionIdOf(socket);
        if (sessionId == null) {
            closeWith(socket, CloseStatus.POLICY_VIOLATION, "Unauthenticated interview socket");
            return;
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            send(socket, RoomEvent.ERROR, Map.of("message", "Malformed event."));
            return;
        }

        RoomEvent event = RoomEvent.fromWireName(payload.path("type").asText(""));
        if (event == null || !event.isClientOriginated()) {
            // Rejecting server-originated types from the client matters: without
            // it a candidate could push their own question.next and skip ahead.
            send(socket, RoomEvent.ERROR, Map.of(
                    "message", "Unsupported event: " + payload.path("type").asText("")));
            return;
        }

        try {
            dispatch(socket, sessionId, event, payload);
        } catch (Exception e) {
            // A failure here must not tear down the socket. The candidate is
            // mid-interview and every answered question is already persisted;
            // dropping them would lose the rest of the interview over one bad
            // message.
            log.error("Interview room event failed: sessionId={} event={}", sessionId, event, e);
            send(socket, RoomEvent.ERROR, Map.of(
                    "message", "Something went wrong handling that. Please continue."));
        }
    }

    private void dispatch(WebSocketSession socket, UUID sessionId, RoomEvent event, JsonNode payload) {
        switch (event) {
            case SESSION_START -> roomService.start(sessionId, socket);

            case ANSWER_SUBMIT -> roomService.submitAnswer(
                    sessionId,
                    socket,
                    payload.path("questionIndex").asInt(-1),
                    payload.path("transcriptText").asText(""),
                    payload.path("durationSeconds").asInt(0));

            case PROCTORING_EVENT -> roomService.recordProctoringEvent(
                    sessionId,
                    payload.path("proctoringType").asText(payload.path("eventType").asText("")),
                    payload.path("timestamp").asText(null));

            case SESSION_END -> roomService.end(sessionId, socket);

            case SESSION_RESUME -> roomService.resume(sessionId, socket);

            default -> send(socket, RoomEvent.ERROR, Map.of("message", "Unhandled event."));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        UUID sessionId = sessionIdOf(socket);
        if (sessionId != null) {
            registry.unregister(sessionId);
            // Deliberately does NOT end the interview. A dropped socket is
            // expected — the candidate's wifi, a laptop lid, a pod rollout — and
            // the room reconnects and resumes. Ending here would turn a blip into
            // a lost interview.
            log.info("Interview socket closed: sessionId={} status={}", sessionId, status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable exception) {
        log.warn("Interview socket transport error: sessionId={} error={}",
                sessionIdOf(socket), exception.getMessage());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID sessionIdOf(WebSocketSession socket) {
        Object value = socket.getAttributes().get(SESSION_ID_ATTRIBUTE);
        return value instanceof UUID id ? id : null;
    }

    private void send(WebSocketSession socket, RoomEvent event, Map<String, Object> payload) {
        RoomMessenger.send(socket, event, payload, objectMapper);
    }

    private void closeWith(WebSocketSession socket, CloseStatus status, String reason) {
        try {
            socket.close(status.withReason(reason));
        } catch (Exception e) {
            log.debug("Failed to close socket: {}", e.getMessage());
        }
    }
}
