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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stricter per-IP rate limiter applied exclusively to
 * {@code POST /api/v1/companies/register} (the public company-onboarding endpoint).
 *
 * <h2>Why a separate filter?</h2>
 * <p>The general {@link RateLimitFilter} covers all auth endpoints at a moderate
 * rate (20 burst / 10 per minute) to defend against credential stuffing and OTP
 * brute-force. Company creation is a heavier operation and a bigger abuse vector —
 * someone could slowly spin up many free tenants without triggering the general limit.
 *
 * <h2>Default limits</h2>
 * <ul>
 *   <li>Capacity: 3 (max burst — e.g. someone onboarding 3 companies in quick succession)</li>
 *   <li>Refill: 3 tokens every 30 minutes per IP</li>
 * </ul>
 * <p>This means an IP can create at most 3 companies per 30-minute window, which is
 * generous for legitimate use but prevents automated bulk creation.
 *
 * <h2>Pass-through behaviour</h2>
 * <p>All requests that are NOT {@code POST /api/v1/companies/register} pass straight
 * through without consuming any tokens. The filter is path-aware and intentionally
 * narrow in scope.
 */
public class OnboardRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OnboardRateLimitFilter.class);

    private static final String ONBOARD_PATH   = "/api/v1/companies/register";
    private static final String ONBOARD_METHOD = HttpMethod.POST.name();

    private final int      capacity;
    private final int      refillTokens;
    private final Duration refillDuration;
    private final ObjectMapper objectMapper;

    /** Per-IP token buckets for the onboarding endpoint only. */
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public OnboardRateLimitFilter(int capacity,
                                  int refillTokens,
                                  Duration refillDuration,
                                  ObjectMapper objectMapper) {
        this.capacity       = capacity;
        this.refillTokens   = refillTokens;
        this.refillDuration = refillDuration;
        this.objectMapper   = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest  request,
                                    HttpServletResponse response,
                                    FilterChain         chain)
            throws ServletException, IOException {

        // Only apply to POST /api/v1/companies/register — all other paths pass through
        if (!ONBOARD_METHOD.equalsIgnoreCase(request.getMethod())
                || !ONBOARD_PATH.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        Bucket bucket   = buckets.computeIfAbsent(clientIp, this::newBucket);

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            log.warn("Onboard rate limit exceeded: ip={}", clientIp);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", String.valueOf(refillDuration.toSeconds()));

            ApiErrorResponse body = ApiErrorResponse.of(
                    ApiErrorResponse.ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many company creation attempts. Please wait " +
                    refillDuration.toMinutes() + " minutes before trying again.");
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

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
