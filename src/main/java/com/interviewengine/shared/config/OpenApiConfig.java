package com.interviewengine.shared.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi configuration.
 *
 * <p>Produces the OpenAPI 3 description consumed by the Swagger UI at
 * {@code /swagger-ui.html} and the raw spec at {@code /v3/api-docs}.
 *
 * <h2>Security scheme</h2>
 * <p>Two schemes are declared so that Swagger UI's "Authorize" dialog
 * can set both the employer JWT and the candidate invite token independently.
 * Controllers that require authentication will show the lock icon automatically.
 *
 * <h2>Production note</h2>
 * <p>The Swagger UI is permit-all in {@code SecurityConfig} for developer
 * convenience. In production, restrict access via network policy or an
 * environment-conditional bean:
 * {@code @ConditionalOnProperty("springdoc.swagger-ui.enabled")}.
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";
    public static final String INVITE_SCHEME = "inviteToken";

    @Bean
    public OpenAPI interviewEngineOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("InterviewEngine API")
                        .description("AI-powered technical interview platform — REST API reference")
                        .version("v1")
                        .contact(new Contact()
                                .name("InterviewEngine Engineering")
                                .email("engineering@interviewengine.ai"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://interviewengine.ai")))
                .components(new Components()
                        // Employer JWT (RS256 access token)
                        .addSecuritySchemes(BEARER_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Employer RS256 access token. " +
                                                "Obtain via POST /{slug}/auth/login."))
                        // Candidate invite token (HS256)
                        .addSecuritySchemes(INVITE_SCHEME,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Candidate invite token. " +
                                                "Delivered via email; use for /candidate/** endpoints.")))
                // Apply bearer auth globally; individual operations can override with @SecurityRequirement
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
