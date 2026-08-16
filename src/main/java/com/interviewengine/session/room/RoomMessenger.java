package com.interviewengine.session.room;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

/**
 * Writes events to an interview socket.
 *
 * <p>Extracted so the handler and the service share one send path, and so the
 * concurrency rule below is stated once.
 */
public final class RoomMessenger {

    private static final Logger log = LoggerFactory.getLogger(RoomMessenger.class);

    private RoomMessenger() {}

    /**
     * Sends one event.
     *
     * <p><strong>Synchronised on the socket.</strong> Spring's
     * {@code WebSocketSession} is not safe for concurrent senders, and this room
     * genuinely has several: the request thread handling {@code answer.submit},
     * and the scheduled timer pushing {@code timer.warning}. Without the lock the
     * two interleave and produce a corrupt frame, which the browser cannot parse
     * and which reads to the candidate as the interview breaking.
     *
     * <p>Failures are logged, never thrown. Every answer is already persisted
     * before a push is attempted, so a failed send costs a redelivery on
     * reconnect rather than data.
     */
    public static void send(WebSocketSession socket,
                            RoomEvent event,
                            Map<String, Object> payload,
                            ObjectMapper objectMapper) {
        if (socket == null || !socket.isOpen()) {
            return;
        }

        Map<String, Object> frame = new HashMap<>(payload == null ? Map.of() : payload);
        frame.put("type", event.wireName());

        try {
            String json = objectMapper.writeValueAsString(frame);
            synchronized (socket) {
                if (socket.isOpen()) {
                    socket.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to send {} on interview socket: {}", event.wireName(), e.getMessage());
        }
    }
}
