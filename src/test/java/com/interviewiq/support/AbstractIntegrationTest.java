package com.interviewiq.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Base class for all Spring Boot integration tests.
 *
 * <ul>
 *   <li>Boots the full application on a random port.</li>
 *   <li>Activates the {@code test} profile (loads application-test.yml).</li>
 *   <li>Wires Postgres via the singleton {@link PostgresTestContainer}.</li>
 *   <li>Stubs out AWS / Razorpay / Recall.ai / OpenAI clients via
 *       {@link StubAwsConfig} and {@link StubExternalConfig}.</li>
 *   <li>Wraps each test method in a transactional rollback for state isolation.</li>
 * </ul>
 *
 * <p>Subclasses receive {@link MockMvc} and {@link ObjectMapper} via field
 * injection. Helper methods (registerCompanyWithAdmin / loginAndGetToken)
 * are intentionally not yet implemented — they live as TODOs in subclasses
 * because they depend on the AuthService / OtpService surfaces.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({StubAwsConfig.class, StubExternalConfig.class})
@Transactional
public abstract class AbstractIntegrationTest {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;

    @BeforeAll
    static void startContainer() {
        PostgresTestContainer.getInstance().start();
    }

    @DynamicPropertySource
    static void registerDatasourceProperties(DynamicPropertyRegistry registry) {
        PostgresTestContainer pg = PostgresTestContainer.getInstance();
        pg.start();
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        registry.add("spring.flyway.url", pg::getJdbcUrl);
        registry.add("spring.flyway.user", pg::getUsername);
        registry.add("spring.flyway.password", pg::getPassword);
    }
}
