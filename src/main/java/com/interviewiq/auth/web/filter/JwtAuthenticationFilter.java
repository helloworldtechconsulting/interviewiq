package com.interviewiq.auth.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.auth.exception.InvalidTokenException;
import com.interviewiq.auth.exception.TokenExpiredException;
import com.interviewiq.auth.service.TokenService;
import com.interviewiq.auth.service.dto.AccessTokenClaims;
import com.interviewiq.shared.dto.ApiErrorResponse;
import com.interviewiq.shared.security.EmployerPrincipal;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Employer JWT authentication filter for the employer {@link org.springframework.security.web.SecurityFilterChain}.
 *
 * <p>Execution flow:
 * <ol>
 *   <li>Extract the {@code Authorization: Bearer <token>} header.</li>
 *   <li>If absent → continue without setting authentication (unauthenticated request;
 *       the security config's {@code .anyRequest().authenticated()} will reject it
 *       via the configured {@code AuthenticationEntryPoint}).</li>
 *   <li>If present but invalid → write a JSON error response directly and abort
 *       the filter chain.  This ensures precise error codes ({@code TOKEN_EXPIRED}
 *       vs {@code INVALID_TOKEN}) instead of a generic 401.</li>
 *   <li>If valid → build an {@link EmployerPrincipal}, wrap it in a
 *       {@link UsernamePasswordAuthenticationToken}, and store it in the
 *       {@link SecurityContextHolder}.</li>
 * </ol>
 *
 * <p>No database call is made for valid tokens — all principal data is encoded
 * in the JWT itself.  This keeps authentication O(1) regardless of load.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService  tokenService;
    private final ObjectMapper  objectMapper;

    public JwtAuthenticationFilter(TokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        // ── No token present — continue without authentication ────────────────
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        // ── Token present — validate ──────────────────────────────────────────
        try {
            AccessTokenClaims claims = tokenService.validateAccessToken(token);

            // Build principal (no DB lookup)
            EmployerPrincipal principal = new EmployerPrincipal(
                    claims.companyId(),
                    claims.userId(),
                    claims.email(),
                    List.of(new SimpleGrantedAuthority("ROLE_" + claims.role().name()))
            );

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("JWT auth OK: userId={} companyId={}", claims.userId(), claims.companyId());

            filterChain.doFilter(request, response);

        } catch (TokenExpiredException e) {
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED,
                    ApiErrorResponse.ErrorCode.TOKEN_EXPIRED, e.getMessage());
        } catch (InvalidTokenException e) {
            writeErrorResponse(response, HttpStatus.UNAUTHORIZED,
                    ApiErrorResponse.ErrorCode.INVALID_TOKEN, e.getMessage());
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
