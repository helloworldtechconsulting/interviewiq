package com.interviewiq.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.auth.service.TokenService;
import com.interviewiq.auth.web.filter.CandidateTokenAuthFilter;
import com.interviewiq.auth.web.filter.JwtAuthenticationFilter;
import com.interviewiq.auth.web.filter.OnboardRateLimitFilter;
import com.interviewiq.auth.web.filter.RateLimitFilter;
import com.interviewiq.shared.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;

/**
 * Spring Security configuration for InterviewIQ.
 *
 * <h2>Two-chain architecture</h2>
 *
 * <p><strong>Chain 1 — Candidate chain ({@code @Order(1)})</strong><br>
 * Matches: {@code /api/v1/candidate/**}<br>
 * Auth: HMAC-SHA256 invite token via {@link CandidateTokenAuthFilter}<br>
 * All paths require authentication.
 *
 * <p><strong>Chain 2 — Employer chain ({@code @Order(2)})</strong><br>
 * Matches: {@code /api/v1/**} and {@code /actuator/**}<br>
 * Auth: RS256 JWT via {@link JwtAuthenticationFilter}<br>
 * Public paths: {@code /api/v1/auth/**}, {@code /api/v1/webhooks/**},
 * {@code /actuator/health}, {@code /actuator/info}.
 *
 * <h2>Why filters are NOT declared as {@code @Bean}</h2>
 * <p>If a {@code Filter} is a Spring bean, Spring Boot's
 * {@code FilterRegistrationAutoConfiguration} adds it as a servlet-level filter
 * for ALL requests — bypassing {@code securityMatcher} separation entirely.
 * Filters are created as plain instances (via {@code new}) and added only to
 * their respective chain via {@code addFilterBefore()}.
 *
 * <h2>Security posture</h2>
 * <ul>
 *   <li>CSRF disabled — stateless JWT API, no session cookies.</li>
 *   <li>Both chains use {@code STATELESS} session creation.</li>
 *   <li>Both entry points write JSON using {@link ApiErrorResponse} — never HTML.</li>
 *   <li>{@code @EnableMethodSecurity} enables {@code @PreAuthorize} for
 *       fine-grained role checks within controllers.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Token-bucket capacity — max burst on auth endpoints before 429. */
    @Value("${app.security.rate-limit.capacity:20}")
    private int rateLimitCapacity;

    /** Tokens refilled per window on auth endpoints. */
    @Value("${app.security.rate-limit.refill-tokens:10}")
    private int rateLimitRefillTokens;

    /** Refill window duration on auth endpoints. */
    @Value("${app.security.rate-limit.refill-duration:PT1M}")
    private Duration rateLimitRefillDuration;

    @Value("${app.security.onboard-rate-limit.capacity:3}")
    private int onboardRateLimitCapacity;

    @Value("${app.security.onboard-rate-limit.refill-tokens:3}")
    private int onboardRateLimitRefillTokens;

    @Value("${app.security.onboard-rate-limit.refill-duration:PT30M}")
    private Duration onboardRateLimitRefillDuration;

    private final TokenService tokenService;
    private final ObjectMapper objectMapper;
    private final SecurityProperties securityProperties;

    public SecurityConfig(TokenService tokenService,
                          ObjectMapper objectMapper,
                          SecurityProperties securityProperties) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.securityProperties = securityProperties;
    }

    // =========================================================================
    // Chain 1 — Candidate (Order 1, matched first)
    // =========================================================================

    /**
     * Handles all {@code /api/v1/candidate/**} requests.
     * Invite token authentication — no employer JWTs accepted here.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain candidateFilterChain(HttpSecurity http) throws Exception {
        // Filters are NOT beans — created as plain instances to prevent servlet-level
        // auto-registration by FilterRegistrationAutoConfiguration.
        CandidateTokenAuthFilter candidateFilter =
                new CandidateTokenAuthFilter(tokenService, objectMapper);

        return http
                .securityMatcher("/api/v1/candidate/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth ->
                        auth.anyRequest().authenticated())
                .addFilterBefore(candidateFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(
                                (req, res, e) -> writeJsonError(res, 401,
                                        ApiErrorResponse.ErrorCode.UNAUTHORIZED,
                                        "Invite token required"))
                        .accessDeniedHandler(
                                (req, res, e) -> writeJsonError(res, 403,
                                        ApiErrorResponse.ErrorCode.FORBIDDEN,
                                        "Access denied")))
                .build();
    }

    // =========================================================================
    // Chain 2 — Employer (Order 2, default for all /api/v1/**)
    // =========================================================================

    /**
     * Handles remaining {@code /api/v1/**} and {@code /actuator/**} requests.
     *
     * <p>Public paths:
     * <ul>
     *   <li>{@code /api/v1/auth/**} — login, register, OTP, Google OAuth, password reset</li>
     *   <li>{@code /api/v1/webhooks/**} — Razorpay + SMTP bounce callbacks; HMAC-verified at service layer</li>
     *   <li>{@code /actuator/health}, {@code /actuator/info} — load-balancer probes</li>
     * </ul>
     */
    @Bean
    @Order(2)
    public SecurityFilterChain employerFilterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationFilter jwtFilter =
                new JwtAuthenticationFilter(tokenService, objectMapper);

        RateLimitFilter rateLimitFilter = new RateLimitFilter(
                rateLimitCapacity, rateLimitRefillTokens, rateLimitRefillDuration, objectMapper);

        OnboardRateLimitFilter onboardRateLimitFilter = new OnboardRateLimitFilter(
                onboardRateLimitCapacity, onboardRateLimitRefillTokens,
                onboardRateLimitRefillDuration, objectMapper);

        return http
                .securityMatcher("/api/v1/**", "/actuator/**", "/v3/api-docs/**",
                        "/swagger-ui/**", "/swagger-ui.html")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(c -> c.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm ->
                        sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/*/auth/**").permitAll()
                        .requestMatchers("/api/v1/auth/google/**").permitAll()
                        .requestMatchers("/api/v1/companies/register").permitAll()
                        .requestMatchers("/api/v1/companies/check-slug").permitAll()
                        .requestMatchers("/api/v1/webhooks/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // OpenAPI / Swagger UI — permit in all environments; restrict in prod via network policy
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(onboardRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(
                                (req, res, e) -> writeJsonError(res, 401,
                                        ApiErrorResponse.ErrorCode.UNAUTHORIZED,
                                        "Authentication required"))
                        .accessDeniedHandler(
                                (req, res, e) -> writeJsonError(res, 403,
                                        ApiErrorResponse.ErrorCode.FORBIDDEN,
                                        "Access denied")))
                .build();
    }

    // =========================================================================
    // Shared beans
    // =========================================================================

    /**
     * BCrypt password encoder with cost factor 12.
     * Matches the production recommendation from migration audit finding L1.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * CORS policy shared by both chains, built from {@code app.security.cors}.
     *
     * <p>PRD v2.1 §7.1.3 makes an absent or permissive CORS policy a launch
     * blocker. Origins are an explicit allow-list — there is no wildcard path
     * through this method. {@code allowCredentials} is on because the refresh
     * token is carried in an HTTP-only cookie (§7.1.1), and the CORS
     * specification forbids combining credentials with an {@code *} origin, so
     * enumerating origins is the only configuration that can work.
     *
     * <p>An empty allow-list yields a policy that permits no cross-origin
     * request. That is the correct default: same-origin deployments need
     * nothing, and anything else must be declared.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        SecurityProperties.Cors cors = securityProperties.getCors();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(cors.getAllowedOrigins());
        config.setAllowedMethods(cors.getAllowedMethods());
        config.setAllowedHeaders(cors.getAllowedHeaders());
        config.setExposedHeaders(cors.getExposedHeaders());
        config.setAllowCredentials(true);
        config.setMaxAge(cors.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Writes a JSON {@link ApiErrorResponse} directly to the servlet response.
     * Used by the entry points and denied handlers, which run outside Spring MVC
     * and therefore cannot use {@link com.interviewiq.shared.web.GlobalExceptionHandler}.
     */
    private void writeJsonError(HttpServletResponse response,
                                int statusCode,
                                String errorCode,
                                String message) {
        try {
            response.setStatus(statusCode);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            ApiErrorResponse body = ApiErrorResponse.of(errorCode, message);
            response.getWriter().write(objectMapper.writeValueAsString(body));
        } catch (Exception ex) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }
}
