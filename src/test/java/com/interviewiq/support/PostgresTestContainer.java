package com.interviewiq.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Postgres Testcontainer reused across the entire test JVM.
 *
 * <p>The container is started once on first access and reused by every
 * {@link AbstractIntegrationTest}. Testcontainers keeps the container alive
 * for the JVM lifetime and tears it down via the Ryuk reaper container.
 *
 * <p>Why a singleton? Spawning a fresh Postgres per test class would multiply
 * suite duration by 5–10×. Tests rely on transactional rollback (or explicit
 * cleanup) to keep state isolated.
 */
public final class PostgresTestContainer extends PostgreSQLContainer<PostgresTestContainer> {

    private static final String IMAGE_NAME = "postgres:15-alpine";

    private static final PostgresTestContainer INSTANCE = new PostgresTestContainer();

    private PostgresTestContainer() {
        super(DockerImageName.parse(IMAGE_NAME));
        withDatabaseName("interviewiq_test");
        withUsername("test");
        withPassword("test");
        // Keep the container reusable across IDE runs (requires
        // ~/.testcontainers.properties: testcontainers.reuse.enable=true)
        withReuse(true);
    }

    public static PostgresTestContainer getInstance() {
        return INSTANCE;
    }

    @Override
    public void start() {
        if (!isRunning()) {
            super.start();
        }
    }

    /**
     * No-op: the container outlives every test class.
     * Testcontainers cleans up via the Ryuk reaper.
     */
    @Override
    public void stop() {
        // Singleton lifecycle managed by JVM shutdown.
    }
}
