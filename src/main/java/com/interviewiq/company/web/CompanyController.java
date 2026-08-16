package com.interviewiq.company.web;

import com.interviewiq.company.dto.CompanyOnboardRequest;
import com.interviewiq.company.dto.CompanyProfileResponse;
import com.interviewiq.company.dto.LogoUploadUrlResponse;
import com.interviewiq.company.dto.OnboardResponse;
import com.interviewiq.company.dto.UpdateCompanyRequest;
import com.interviewiq.company.service.CompanyService;
import com.interviewiq.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Company management endpoints.
 *
 * <h2>Public endpoints (no auth required)</h2>
 * <ul>
 *   <li>{@code POST /api/v1/companies/register} — onboarding: creates company + admin + wallet</li>
 *   <li>{@code GET  /api/v1/companies/check-slug} — slug availability check</li>
 * </ul>
 *
 * <h2>Authenticated endpoints (employer JWT required)</h2>
 * <ul>
 *   <li>{@code GET   /api/v1/companies/me} — get caller's company profile</li>
 *   <li>{@code PATCH /api/v1/companies/me} — update caller's company profile (ADMIN only)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/companies")
@Validated
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    // =========================================================================
    // Public endpoints
    // =========================================================================

    /**
     * POST /api/v1/companies/register
     *
     * <p>Atomically creates: company + first admin user + empty wallet.
     * Dispatches an email-verification OTP; login is blocked until OTP is verified.
     * Returns 201 with the slug and email so the frontend knows where to redirect.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OnboardResponse> register(
            @Valid @RequestBody CompanyOnboardRequest request) {
        return ApiResponse.created(companyService.onboard(request));
    }

    /**
     * GET /api/v1/companies/check-slug?slug=acme-corp
     *
     * <p>Returns {@code {"available": true/false}} so the frontend can give
     * instant feedback as the user types a slug during onboarding.
     */
    @GetMapping("/check-slug")
    public ApiResponse<Map<String, Boolean>> checkSlug(
            @RequestParam
            @NotBlank(message = "Slug parameter is required.")
            @Size(min = 3, max = 100, message = "Slug must be between 3 and 100 characters.")
            @Pattern(regexp = "^[a-z0-9-]+$", message = "Slug may only contain lowercase letters, digits, and hyphens.")
            String slug) {
        return ApiResponse.ok(Map.of("available", companyService.isSlugAvailable(slug)));
    }

    // =========================================================================
    // Authenticated endpoints
    // =========================================================================

    /**
     * GET /api/v1/companies/me
     *
     * <p>Returns the authenticated employer's company profile.
     * Company ID comes from the JWT — no path variable needed.
     */
    @GetMapping("/me")
    public ApiResponse<CompanyProfileResponse> getProfile() {
        return ApiResponse.ok(companyService.getProfile());
    }

    /**
     * PATCH /api/v1/companies/me
     *
     * <p>Partially updates the company profile. Only {@code ADMIN} users may call
     * this — {@code @PreAuthorize} enforces the role guard before the method runs.
     * Fields set to {@code null} in the request body are left unchanged.
     */
    @PatchMapping("/me")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CompanyProfileResponse> updateProfile(
            @Valid @RequestBody UpdateCompanyRequest request) {
        return ApiResponse.ok(companyService.updateProfile(request));
    }

    // =========================================================================
    // Logo
    // =========================================================================

    /**
     * GET /api/v1/companies/me/logo-upload-url?contentType=image/png
     *
     * <p>ADMIN only, on the same reasoning as profile updates: the logo appears on
     * candidate-facing invite emails and the interview room, so it is brand
     * surface rather than a personal setting.
     */
    @GetMapping("/me/logo-upload-url")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<LogoUploadUrlResponse> getLogoUploadUrl(
            @RequestParam
            @NotBlank(message = "contentType is required.")
            String contentType) {
        return ApiResponse.ok(companyService.generateLogoUploadUrl(contentType));
    }

    /** POST /api/v1/companies/me/logo-upload-confirm */
    @PostMapping("/me/logo-upload-confirm")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CompanyProfileResponse> confirmLogoUpload(
            @RequestBody Map<String, String> body) {
        return ApiResponse.ok(companyService.confirmLogoUploaded(body.get("objectKey")));
    }

    /**
     * GET /api/v1/companies/me/logo-url
     *
     * <p>Returns {@code {"logoUrl": null}} when no logo has been uploaded, rather
     * than 404 — "this company has no logo" is a normal answer, not a missing
     * resource, and a 404 here would make every client special-case it.
     */
    @GetMapping("/me/logo-url")
    public ApiResponse<Map<String, String>> getLogoUrl() {
        Map<String, String> body = new HashMap<>();
        body.put("logoUrl", companyService.getLogoDownloadUrl());
        return ApiResponse.ok(body);
    }
}
