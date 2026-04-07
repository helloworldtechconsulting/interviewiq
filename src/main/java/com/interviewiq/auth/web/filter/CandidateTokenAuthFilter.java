package com.interviewiq.auth.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.auth.exception.InvalidTokenException;
import com.interviewiq.auth.exception.TokenExpiredException;
import com.interviewiq.auth.service.TokenService;
import com.interviewiq.auth.service.dto.InviteTokenClaims;
import com.interviewiq.shared.dto.ApiErrorResponse;
import com.interviewiq.shared.security.CandidatePrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Candidate invite-token authentication filter for the candidate
 * {@link org.springframework.security.web.SecurityFilterChain}.
 *
 * <p>Candidates authenticate via a one-time HMAC-SHA256 signed JWT invite token
 * embedded in their email link.  The frontend receives the token and sends it on
 * every API request as:
 * <pre>Authorization: Bearer &lt;invite-token&gt;</pre>
 *
 * <p>Execution flow:
 * <ol>
 *   <li>Extract the {@code Authorization: Bearer <token>} header.</li>
 *   <li>If absent → write 401 immediately (candidate paths are always authenticated).</li>
 *   <li>If present but invalid or expired → write a JSON error response with a
 *       precise error code and abort the chain.</li>
 *   <li>If valid → build a {@link CandidatePrincipal} and store in the
 *       {@link SecurityContextHolder}.</li>
 * </ol>
 *
 * <p>This filter is installed in the candidate chain only (paths matching
 * {@code /api/v1/candidate/**}).  It is never applied to employer paths.
 */
public class CandidateTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CandidateTokenAuthFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    public CandidateTokenAuthFilter(TokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        // ── Candidate paths always require a token ────────────────────────────
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED,
                    ApiErrorResponse.ErrorCode.UNAUTHORIZED,
                    "Invite token required");
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        try {
            InviteTokenClaims claims = tokenService.validateInviteToken(token);

            CandidatePrincipal principal = new CandidatePrincipal(
                    claims.companyId(),
                    claims.sessionId(),
                    claims.candidateId()
            );

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Invite token auth OK: sessionId={} candidateId={}",
                    claims.sessionId(), claims.candidateId());

            filterChain.doFilter(request, response);

        } catch (TokenExpiredException e) {
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED,
                    ApiErrorResponse.ErrorCode.TOKEN_EXPIRED,
                    "Your interview invite link has expired. Please request a new one.");
        } catch (InvalidTokenException e) {
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED,
                    ApiErrorResponse.ErrorCode.INVALID_TOKEN,
                    "Your interview invite link is invalid.");
        }
    }

    private void writeErrorResponse(HttpServletResponse response,
                                    HttpStatus status,
                                    String errorCode,
                                    String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ApiErrorResponse body = ApiErrorResponse.of(errorCode, message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
