package com.interviewiq.support;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for JPA integration tests that need a real PostgreSQL instance with the
 * full Flyway-migrated schema (CHECK constraints, triggers, composite FKs).
 *
 * <p>Uses {@link DataJpaTest} to load only the JPA slice — repositories, entities, and
 * the {@code DataSource} — so the heavy production beans (Razorpay, S3/SES, Spring AI)
 * are never initialised and cannot fail the context in a test environment. Real service
 * beans under test are supplied explicitly via {@code @Import} on the concrete class.
 *
 * <p>{@code replace = NONE} keeps the Testcontainers datasource instead of an embedded
 * database, and {@code ddl-auto = validate} lets Flyway own the schema exactly as in
 * production.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
public abstract class AbstractPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("interviewiq_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // Flyway owns the schema; load only versioned migrations (never db/seed).
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
}
