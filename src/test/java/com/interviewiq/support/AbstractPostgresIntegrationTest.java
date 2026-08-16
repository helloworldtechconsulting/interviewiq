package com.interviewiq.support;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

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
 *
 * <h2>Why the container is a manually started singleton</h2>
 *
 * <p>This class previously used {@code @Testcontainers} with {@code @Container}, which
 * starts and stops the container around <em>each test class</em>. That is wrong here,
 * and it fails in a way that is easy to misread.
 *
 * <p>Spring caches an {@code ApplicationContext} and reuses it across test classes with
 * matching configuration — which all subclasses of this one have. The first class starts
 * a container, {@code @DynamicPropertySource} records its randomly mapped port, and the
 * context is built and cached. When that class finishes, the extension <em>stops</em> the
 * container. The next class starts a new one on a different port, but Spring hands it the
 * cached context, still pointing at the port that is now closed. Every test in every class
 * after the first fails with "Connection refused" after a twenty-second pool timeout.
 *
 * <p>Starting one container in a static initialiser and never stopping it keeps the URL
 * valid for the life of the JVM, which is what the cached context requires. Testcontainers'
 * Ryuk sidecar removes it when the JVM exits, so nothing leaks.
 *
 * <p>This was invisible until INTIQ-100 added the Failsafe plugin. No execution had ever
 * been bound to run {@code *IT} classes, so all of them compiled and none of them ran.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class AbstractPostgresIntegrationTest {

    /**
     * One container for the whole JVM run.
     *
     * <p>Deliberately not annotated with {@code @Container} and deliberately never
     * stopped — see the class javadoc.
     */
    protected static final PostgreSQLContainer<?> POSTGRES =
            // PostgreSQL 16, matching the managed version the PRD specifies (§9).
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("interviewiq_test")
                    .withUsername("test")
                    .withPassword("test")
                    // Reused across every IT class in the run, so a single startup
                    // cost is amortised rather than paid four times.
                    .withReuse(false);

    static {
        POSTGRES.start();
    }

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
