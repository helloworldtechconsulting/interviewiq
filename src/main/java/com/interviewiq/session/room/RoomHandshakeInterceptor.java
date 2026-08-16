package com.interviewiq.session.room;

import com.interviewiq.auth.service.TokenService;
import com.interviewiq.auth.service.dto.InviteTokenClaims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

/**
 * Authenticates the WebSocket upgrade (PRD v2.1 §7.1.2, §7.5.2).
 *
 * <p>The room connects to
 * {@code wss://api.interviewiq.in/ws/session/{sessionId}?token={candidateJWT}},
 * and the token is verified here, at handshake, before any frame is exchanged.
 *
 * <h2>Why the token is in the query string</h2>
 *
 * <p>Not a shortcut: the browser {@code WebSocket} constructor cannot set request
 * headers, so an {@code Authorization} header is not available on the upgrade.
 * The mitigations are that the token is a candidate JWT with a 2-hour TTL scoped
 * to exactly one session and granting access to nothing else on the platform, the
 * connection is {@code wss}, and query strings are not logged by the ingress.
 *
 * <h2>The check that matters</h2>
 *
 * <p>The session id in the path must match the session the token was issued for.
 * Without that comparison a candidate could take their own valid token and open a
 * socket for someone else's interview — the token would verify, and they would
 * receive another candidate's questions and be able to submit answers as them.
 */
@Component
public class RoomHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RoomHandshakeInterceptor.class);

    private final TokenService tokenService;

    public RoomHandshakeInterceptor(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler handler,
                                   Map<String, Object> attributes) {

        UUID pathSessionId = sessionIdFromPath(request);
        if (pathSessionId == null) {
            return reject(response, "Malformed interview room URL");
        }

        String token = queryParam(request, "token");
        if (token == null || token.isBlank()) {
            return reject(response, "Missing candidate token");
        }

        InviteTokenClaims claims;
        try {
            claims = tokenService.validateInviteToken(token);
        } catch (Exception e) {
            log.warn("Interview socket handshake rejected: invalid token for session {}", pathSessionId);
            return reject(response, "Invalid or expired candidate token");
        }

        if (!pathSessionId.equals(claims.sessionId())) {
            // A valid token for a different interview. This is the cross-session
            // access the session-scoped token exists to prevent.
            log.warn("Interview socket handshake rejected: token for session {} used on session {}",
                    claims.sessionId(), pathSessionId);
            return reject(response, "This token is not valid for that interview");
        }

        attributes.put(InterviewRoomHandler.SESSION_ID_ATTRIBUTE, pathSessionId);
        attributes.put("candidateId", claims.candidateId());
        attributes.put("companyId", claims.companyId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler handler,
                               Exception exception) {
        // Nothing to do — registration happens in afterConnectionEstablished.
    }

    private UUID sessionIdFromPath(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == path.length() - 1) {
            return null;
        }
        try {
            return UUID.fromString(path.substring(lastSlash + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String queryParam(ServerHttpRequest request, String name) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            return servletRequest.getServletRequest().getParameter(name);
        }
        return null;
    }

    private boolean reject(ServerHttpResponse response, String reason) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        log.debug("Interview socket handshake rejected: {}", reason);
        return false;
    }
}
