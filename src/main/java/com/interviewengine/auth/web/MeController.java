package com.interviewengine.auth.web;

import com.interviewengine.auth.domain.User;
import com.interviewengine.auth.dto.MeResponse;
import com.interviewengine.auth.infrastructure.UserRepository;
import com.interviewengine.company.domain.Company;
import com.interviewengine.company.infrastructure.CompanyRepository;
import com.interviewengine.shared.dto.ApiResponse;
import com.interviewengine.shared.exception.AuthorizationException;
import com.interviewengine.shared.security.EmployerPrincipal;
import com.interviewengine.shared.security.SecurityContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/auth/me} — who the caller currently is, read from the
 * database rather than from their token (PRD v2.1 §11).
 *
 * <h2>Why this is not under {@code /api/v1/{slug}/auth}</h2>
 *
 * <p>{@link com.interviewengine.auth.config.SecurityConfig} makes
 * {@code /api/v1/*&#47;auth/**} {@code permitAll} — it has to, because register,
 * login and password-reset all live there and none of them can require a token.
 * Mounting {@code /me} inside that prefix would have made it publicly
 * reachable. The slug is redundant here in any case: the access token already
 * identifies the company, and trusting a slug from the URL over the one in the
 * token would be a tenant-crossing bug waiting to happen.
 *
 * <p>At this path the request falls through to {@code anyRequest().authenticated()},
 * which is the intent.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class MeController {

    private final UserRepository    userRepository;
    private final CompanyRepository companyRepository;

    public MeController(UserRepository userRepository, CompanyRepository companyRepository) {
        this.userRepository    = userRepository;
        this.companyRepository = companyRepository;
    }

    /**
     * Returns the signed-in user and their company as they are <em>now</em>.
     *
     * <p>A token that authenticates but whose user or company row no longer
     * exists is treated as an authorisation failure rather than a 404. The
     * caller is holding a credential that no longer refers to anything, and
     * "this token is no longer good" is the honest answer; a 404 would suggest
     * the endpoint itself was wrong.
     */
    @GetMapping("/me")
    @Transactional(readOnly = true)
    public ApiResponse<MeResponse> me() {
        EmployerPrincipal principal = SecurityContext.requireEmployer();

        User user = userRepository.findByCompanyIdAndId(principal.companyId(), principal.userId())
                .orElseThrow(AuthorizationException::accessDenied);

        Company company = companyRepository.findById(principal.companyId())
                .orElseThrow(AuthorizationException::accessDenied);

        return ApiResponse.ok(MeResponse.of(user, company));
    }
}
