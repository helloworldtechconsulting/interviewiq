package com.interviewiq.auth;

import com.interviewiq.auth.dto.AuthResponse;
import com.interviewiq.auth.dto.GoogleOAuthRequest;
import com.interviewiq.auth.dto.LoginRequest;
import com.interviewiq.auth.dto.RefreshTokenRequest;
import com.interviewiq.auth.dto.RegisterRequest;
import com.interviewiq.common.BadRequestException;
import com.interviewiq.common.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.findByEmail(request.email()).isPresent()) {
            throw new BadRequestException("Email already registered");
        }

        Company company = Company.builder()
                .name(request.companyName())
                .domain(request.companyDomain())
                .status(CompanyStatus.ACTIVE)
                .walletBalancePaise(0L)
                .build();
        company = companyRepository.save(company);

        User user = User.builder()
                .companyId(company.getId())
                .email(request.email())
                .name(request.name())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.ADMIN)
                .emailVerified(false)
                .build();
        user = userRepository.save(user);

        log.info("New user registered: {} for company: {}", user.getEmail(), company.getName());

        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password())
            );

            User user = (User) auth.getPrincipal();
            log.info("User logged in: {}", user.getEmail());
            return buildAuthResponse(user);
        } catch (Exception e) {
            log.warn("Failed login attempt for email: {}", request.email());
            throw new BadRequestException("Invalid email or password");
        }
    }

    public AuthResponse refreshToken(RefreshTokenRequest request) {
        if (!jwtUtil.isTokenValid(request.refreshToken())) {
            throw new BadRequestException("Invalid refresh token");
        }

        if (jwtUtil.isTokenExpired(request.refreshToken())) {
            throw new BadRequestException("Refresh token has expired");
        }

        String email = jwtUtil.extractEmail(request.refreshToken());
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));

        log.info("Token refreshed for user: {}", user.getEmail());
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse googleOAuth(GoogleOAuthRequest request) {
        // Note: In production, verify the ID token with Google
        // For now, we extract claims directly (implement token verification in production)

        // Check if user exists by email
        User existingUser = userRepository.findByEmail(request.idToken()).orElse(null);

        if (existingUser != null) {
            log.info("Google OAuth login for existing user: {}", existingUser.getEmail());
            return buildAuthResponse(existingUser);
        }

        // Create new user and company via Google OAuth
        if (request.companyName() == null || request.companyDomain() == null) {
            throw new BadRequestException("Company name and domain required for new accounts");
        }

        Company company = Company.builder()
                .name(request.companyName())
                .domain(request.companyDomain())
                .status(CompanyStatus.ACTIVE)
                .walletBalancePaise(0L)
                .build();
        company = companyRepository.save(company);

        User user = User.builder()
                .companyId(company.getId())
                .email(request.idToken())
                .name(request.idToken())
                .googleSub(request.idToken())
                .role(UserRole.ADMIN)
                .emailVerified(true)
                .build();
        user = userRepository.save(user);

        log.info("New user created via Google OAuth: {} for company: {}", user.getEmail(), company.getName());
        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);

        return new AuthResponse(
                user.getId(),
                user.getCompanyId(),
                user.getEmail(),
                user.getName(),
                user.getRole().name(),
                accessToken,
                refreshToken,
                60L * 60  // 1 hour in seconds
        );
    }
}
