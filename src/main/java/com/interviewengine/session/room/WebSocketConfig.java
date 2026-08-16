package com.interviewengine.session.room;

import com.interviewengine.auth.config.SecurityProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the interview room socket (PRD v2.1 §6.1, §7.5.2).
 *
 * <p>A raw {@code TextWebSocketHandler} at {@code /ws/session/{sessionId}} — not
 * STOMP. The contract is eight events on one connection with no routing,
 * subscriptions or broker semantics, so STOMP's framing would be pure overhead on
 * a path where the whole point is that the server stays cheap enough for one pod
 * to carry dozens of interviews.
 *
 * <h2>Deployment requirements this depends on</h2>
 *
 * <p>The socket is terminated by the ingress controller with <strong>session
 * affinity</strong>, which §7.9 requires because the session registry is
 * in-memory and per-pod: a candidate's WebSocket <em>and</em> their REST calls
 * must reach the same pod. The corresponding ingress annotations are in the
 * workload Terraform module:
 *
 * <pre>
 * nginx.ingress.kubernetes.io/affinity: "cookie"
 * nginx.ingress.kubernetes.io/session-cookie-name: "iiq-affinity"
 * nginx.ingress.kubernetes.io/proxy-read-timeout: "3900"
 * </pre>
 *
 * <p>The read timeout is not arbitrary — it must exceed the longest interview
 * (60 minutes at the Comprehensive tier) or the ingress closes a socket during a
 * live interview.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    /**
     * Maximum time a candidate may be silent before the container reaps the
     * socket. Generous because a candidate thinking through a hard question is
     * not idle, and the room only sends on question boundaries.
     */
    private static final int IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000;

    /**
     * Transcripts are the largest thing that crosses this socket. A long answer
     * to a Comprehensive-tier question is a few KB; 64 KB is generous headroom
     * and still bounds what a client can push in one frame.
     */
    private static final int MAX_TEXT_MESSAGE_SIZE = 64 * 1024;

    private final InterviewRoomHandler handler;
    private final RoomHandshakeInterceptor handshakeInterceptor;
    private final SecurityProperties securityProperties;

    public WebSocketConfig(InterviewRoomHandler handler,
                           RoomHandshakeInterceptor handshakeInterceptor,
                           SecurityProperties securityProperties) {
        this.handler              = handler;
        this.handshakeInterceptor = handshakeInterceptor;
        this.securityProperties   = securityProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/session/*")
                .addInterceptors(handshakeInterceptor)
                // Same explicit origin allow-list as the REST API (§7.1.3).
                // WebSocket upgrades are not covered by the CORS filter, so
                // omitting this would leave the socket open to any origin while
                // the REST API beside it was restricted.
                .setAllowedOrigins(securityProperties.getCors().getAllowedOrigins()
                        .toArray(String[]::new));
    }

    /**
     * Container limits for the socket.
     *
     * <p>Registered as a bean rather than set on the handler because the servlet
     * container owns these, and an unbounded idle timeout would leak sockets from
     * candidates who close their laptop mid-interview without disconnecting.
     */
    @org.springframework.context.annotation.Bean
    public org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean
            webSocketContainer() {
        var container = new org.springframework.web.socket.server.standard
                .ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_SIZE);
        container.setMaxSessionIdleTimeout((long) IDLE_TIMEOUT_MILLIS);
        return container;
    }
}
