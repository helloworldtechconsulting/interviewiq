package com.interviewiq.company;

import com.interviewiq.support.AbstractIntegrationTest;
import com.interviewiq.support.TestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for company onboarding endpoints.
 *
 * <ul>
 *   <li>POST {@code /api/v1/companies/register} — happy path</li>
 *   <li>POST {@code /api/v1/companies/register} — slug uniqueness collision</li>
 *   <li>GET  {@code /api/v1/companies/check-slug} — availability flag</li>
 * </ul>
 *
 * <p>NOTE: this test is best-effort against the public schema. If the company
 * onboarding response shape diverges from {@code OnboardResponse(slug,email)}
 * the JSON-path assertions below need to be updated.
 */
class CompanyOnboardingIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("POST /companies/register returns 201 with slug + email payload")
    void register_happyPath() throws Exception {
        Map<String, Object> body = TestDataFactory.companyOnboardRequest(
                "Acme Onboard", TestDataFactory.uniqueEmail());

        mockMvc.perform(post("/api/v1/companies/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.slug").exists())
                .andExpect(jsonPath("$.data.email").exists());
    }

    @Test
    @DisplayName("POST /companies/register rejects duplicate slug with 409")
    void register_duplicateSlugRejected() throws Exception {
        String slug = TestDataFactory.uniqueSlug();
        Map<String, Object> first = TestDataFactory.companyOnboardRequest(
                "Acme First", TestDataFactory.uniqueEmail());
        first.put("slug", slug);

        Map<String, Object> second = TestDataFactory.companyOnboardRequest(
                "Acme Second", TestDataFactory.uniqueEmail());
        second.put("slug", slug);

        mockMvc.perform(post("/api/v1/companies/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isCreated());

        // Second registration with the same slug must fail (409 or 400 depending
        // on the implementation — either is acceptable for this assertion).
        mockMvc.perform(post("/api/v1/companies/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(second)))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status != 409 && status != 400) {
                        throw new AssertionError(
                                "Expected 409 or 400 for duplicate slug, got " + status);
                    }
                });
    }

    @Test
    @DisplayName("GET /companies/check-slug returns availability flag")
    void checkSlug_returnsAvailabilityBoolean() throws Exception {
        mockMvc.perform(get("/api/v1/companies/check-slug")
                        .param("slug", TestDataFactory.uniqueSlug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").exists());
    }
}
