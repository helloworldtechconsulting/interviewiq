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

public class OnboardRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OnboardRateLimitFilter.class);

    private static final String ONBOARD_PATH   = "/api/v1/companies/register";
    private static final String ONBOARD_METHOD = HttpMethod.POST.name();

    private final int      capacity;
    private final int      refillTokens;
    private final Duration refillDuration;
    private final ObjectMapper objectMapper;

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
