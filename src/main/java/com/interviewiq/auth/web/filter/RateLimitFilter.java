package com.interviewiq.auth.web.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.shared.dto.ApiErrorResponse;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servlet filter that enforces per-IP token-bucket rate limiting on auth endpoints.
 *
 * <h2>Why this matters</h2>
 * <p>The OTP endpoints ({@code POST /auth/register}, {@code POST /auth/login},
 * {@code POST /auth/forgot-password}, {@code POST /auth/resend-verification}) and
 * the password-reset endpoint are the principal brute-force attack surface:
 * <ul>
 *   <li>OTP codes are 6-digit (1-in-1,000,000 chance per guess).</li>
 *   <li>Without rate limiting, an attacker can exhaust all codes in seconds.</li>
 *   <li>Login is vulnerable to credential stuffing without a request budget.</li>
 * </ul>
 *
 * <h2>Algorithm: token bucket</h2>
 * <p>Each client IP gets its own bucket that starts full and refills at a fixed rate.
 * Default limits (overridable in config): 20 tokens capacity, 10 tokens per minute.
 * A request costs 1 token; when the bucket is empty the request is rejected with 429.
 *
 * <h2>Storage: in-process ConcurrentHashMap</h2>
 * <p>Buckets are stored in a {@link ConcurrentHashMap} keyed by client IP. This is
 * correct for single-instance deployments. For multi-instance (Kubernetes), move
 * state to Redis using {@code bucket4j-redis} — no algorithm change required.
 *
 * <h2>Bucket lifecycle</h2>
 * <p>Buckets are created lazily on first request. They are never explicitly evicted
 * in this implementation; memory footprint is bounded by the number of distinct
 * client IPs seen since startup (acceptable for most SaaS deployments; a production
 * hardening step would add a Caffeine cache with TTL expiry).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Tokens added per refill window (greedy refill, not smooth). */
    private final int    refillTokens;
    /** Duration of each refill window. */
    private final Duration refillDuration;
    /** Maximum burst capacity (bucket size). */
    private final int    capacity;

    private final ObjectMapper objectMapper;

    /** Per-IP buckets. Lazily initialised. */
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(int capacity, int refillTokens, Duration refillDuration,
                           ObjectMapper objectMapper) {
        this.capacity       = capacity;
        this.refillTokens   = refillTokens;
        this.refillDuration = refillDuration;
        this.objectMapper   = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         filterChain)
            throws ServletException, IOException {

        String clientIp = resolveClientIp(request);
        Bucket bucket   = buckets.computeIfAbsent(clientIp, this::newBucket);

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded: ip={} path={}", clientIp, request.getRequestURI());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", String.valueOf(refillDuration.toSeconds()));

            ApiErrorResponse body = ApiErrorResponse.of(
                    ApiErrorResponse.ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many requests. Please wait before trying again.");
            response.getWriter().write(objectMapper.writeValueAsString(body));
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Bucket newBucket(String ip) {
        Bandwidth limit = Bandwidth.classic(
                capacity,
                Refill.greedy(refillTokens, refillDuration));
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Resolves the real client IP, honouring the {@code X-Forwarded-For} header
     * set by load balancers. The leftmost IP in the header is the original client.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
