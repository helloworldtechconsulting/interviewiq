package com.interviewiq.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@RequiredArgsConstructor
public class InterviewWebSocketHandler extends TextWebSocketHandler {

    private final InterviewSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = extractSessionId(session);
        activeSessions.put(sessionId, session);
        log.info("WebSocket connected for session: {}", sessionId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            String payload = message.getPayload();
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);

            String messageType = (String) data.get("type");
            String sessionId = (String) data.get("sessionId");

            switch (messageType) {
                case "OFFER":
                    handleWebRtcOffer(sessionId, data);
                    break;
                case "ANSWER":
                    handleWebRtcAnswer(sessionId, data);
                    break;
                case "ICE_CANDIDATE":
                    handleIceCandidate(sessionId, data);
                    break;
                case "TRANSCRIPT":
                    handleTranscript(sessionId, data);
                    break;
                case "ANTI_CHEAT":
                    handleAntiCheatFlag(sessionId, data);
                    break;
                case "NEXT_QUESTION":
                    handleNextQuestion(sessionId, data);
                    break;
                default:
                    log.warn("Unknown message type: {}", messageType);
            }
        } catch (Exception e) {
            log.error("Error handling WebSocket message", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = extractSessionId(session);
        activeSessions.remove(sessionId);
        log.info("WebSocket closed for session: {} with status: {}", sessionId, status);
    }

    private void handleWebRtcOffer(String sessionId, Map<String, Object> data) {
        log.debug("WebRTC offer received for session: {}", sessionId);
        broadcastToSession(sessionId, "OFFER", data.get("offer"));
    }

    private void handleWebRtcAnswer(String sessionId, Map<String, Object> data) {
        log.debug("WebRTC answer received for session: {}", sessionId);
        broadcastToSession(sessionId, "ANSWER", data.get("answer"));
    }

    private void handleIceCandidate(String sessionId, Map<String, Object> data) {
        log.debug("ICE candidate received for session: {}", sessionId);
        broadcastToSession(sessionId, "ICE_CANDIDATE", data.get("candidate"));
    }

    private void handleTranscript(String sessionId, Map<String, Object> data) {
        log.debug("Transcript received for session: {}", sessionId);
        // Store transcript in database
    }

    private void handleAntiCheatFlag(String sessionId, Map<String, Object> data) {
        String flag = (String) data.get("flag");
        log.warn("Anti-cheat flag detected for session {}: {}", sessionId, flag);
        // Store anti-cheat flags in database
    }

    private void handleNextQuestion(String sessionId, Map<String, Object> data) {
        log.debug("Request for next question in session: {}", sessionId);
        // Send next question to client
        broadcastToSession(sessionId, "NEXT_QUESTION", null);
    }

    private void broadcastToSession(String sessionId, String messageType, Object data) {
        WebSocketSession session = activeSessions.get(sessionId);
        if (session != null && session.isOpen()) {
            try {
                Map<String, Object> response = new ConcurrentHashMap<>();
                response.put("type", messageType);
                response.put("data", data);

                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
            } catch (IOException e) {
                log.error("Error sending WebSocket message to session: {}", sessionId, e);
            }
        }
    }

    private String extractSessionId(WebSocketSession session) {
        // Extract session ID from URI path
        String uri = session.getUri().toString();
        String[] parts = uri.split("/");
        return parts[parts.length - 1];
    }
}
