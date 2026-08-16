package com.interviewengine.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.status.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards {@code logback-spring.xml}.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>A logging configuration is only exercised at runtime, and it fails
 * quietly: Logback reports a broken config to its own status listener and then
 * falls back to a default appender. The application starts, logs keep flowing,
 * and the only symptom is that the aggregator stops receiving parseable lines —
 * usually noticed during the first incident that needs them.
 *
 * <p>Nothing else in the suite would catch it. The integration tests are
 * {@code @DataJpaTest} slices, and no {@code @SpringBootTest} exists, so no
 * test loads this file at all. This one configures Logback directly from the
 * shipped resource and asserts the deployed shape is what it claims to be.
 */
class LogbackConfigurationTest {

    /**
     * Initialises the real logging system from the shipped file with one
     * profile active, and hands back the resulting context.
     *
     * <p>Goes through {@link LoggingSystem} rather than driving Joran directly
     * for one reason: the {@code <springProfile>} and {@code <springProperty>}
     * elements are Spring Boot extensions, and a plain {@code JoranConfigurator}
     * skips them <em>without reporting an error</em>. Configured that way, every
     * appender in this file disappears and the assertions below would pass
     * against an empty configuration — the test would be measuring nothing.
     *
     * <p>Boot's own configurator is package-private, so the public entry point
     * is this one. It mutates the JVM-wide logger context, which is why
     * {@link #restoreDefaultLogging()} puts it back.
     */
    private LoggerContext configureWithProfile(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        environment.setProperty("spring.application.name", "interviewengine");

        LoggingSystem system = LoggingSystem.get(getClass().getClassLoader());
        system.cleanUp();
        system.initialize(
                new LoggingInitializationContext(environment),
                "classpath:logback-spring.xml",
                null);

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.putProperty("HOSTNAME", "test-pod-1");
        return context;
    }

    /**
     * Leaves logging as it was found. Surefire shares a JVM across test
     * classes, so a class that reconfigures logging and walks away makes every
     * later class log through whatever it happened to leave behind.
     */
    @AfterAll
    static void restoreDefaultLogging() {
        LoggingSystem system = LoggingSystem.get(LogbackConfigurationTest.class.getClassLoader());
        system.cleanUp();
        system.beforeInitialize();
        system.initialize(new LoggingInitializationContext(new MockEnvironment()), null, null);
    }

    private static List<Status> errorsIn(LoggerContext context) {
        return context.getStatusManager().getCopyOfStatusList().stream()
                .filter(s -> s.getLevel() == Status.ERROR)
                .toList();
    }

    /**
     * The file must parse cleanly. Note that {@code doConfigure} does not throw
     * on most mistakes — a misspelled provider or a bad class name is reported
     * through the status manager and then ignored — so the status list is the
     * assertion that matters, not the absence of an exception.
     */
    @Test
    void theConfigurationParsesWithoutErrorsForEveryProfile() {
        for (String profile : List.of("local", "prod", "staging", "web", "worker")) {
            LoggerContext context = configureWithProfile(profile);
            assertThat(errorsIn(context))
                    .as("logback-spring.xml under profile '%s'", profile)
                    .isEmpty();
        }
    }

    /**
     * The point of the whole file: a deployed pod emits JSON, not the pattern
     * layout that was in force before it existed.
     */
    @Test
    void deployedProfilesEmitOneJsonObjectPerLine() throws Exception {
        LoggerContext context = configureWithProfile("prod");

        var appender = context.getLogger("ROOT").getAppender("JSON");
        assertThat(appender)
                .as("prod must use the JSON appender")
                .isNotNull();

        String line = encodeOneEvent(context, appender);
        JsonNode json = new ObjectMapper().readTree(line);

        assertThat(json.path("message").asText()).isEqualTo("interview started");
        assertThat(json.path("level").asText()).isEqualTo("INFO");
        assertThat(json.path("service").asText()).isEqualTo("interviewengine");
    }

    /**
     * traceId has to reach the log line, or the tracing dependency buys
     * nothing for debugging: spans in a collector that cannot be tied back to
     * the log entries around them are far less useful than they look.
     */
    @Test
    void mdcValuesSuchAsTraceIdAppearAsJsonFields() throws Exception {
        LoggerContext context = configureWithProfile("prod");
        var appender = context.getLogger("ROOT").getAppender("JSON");

        MDC.put("traceId", "abc123");
        try {
            String line = encodeOneEvent(context, appender);
            JsonNode json = new ObjectMapper().readTree(line);
            assertThat(json.path("traceId").asText()).isEqualTo("abc123");
        } finally {
            MDC.remove("traceId");
        }
    }

    /** A developer's terminal should not fill up with JSON. */
    @Test
    void localKeepsTheHumanReadablePattern() {
        LoggerContext context = configureWithProfile("local");

        assertThat(context.getLogger("ROOT").getAppender("CONSOLE"))
                .as("local must keep the console pattern appender")
                .isNotNull();
        assertThat(context.getLogger("ROOT").getAppender("JSON"))
                .as("local must not emit JSON")
                .isNull();
    }

    private String encodeOneEvent(LoggerContext context, ch.qos.logback.core.Appender<ILoggingEvent> appender) {
        var encoder = (LoggingEventCompositeJsonEncoder)
                ((ch.qos.logback.core.OutputStreamAppender<ILoggingEvent>) appender).getEncoder();

        LoggingEvent event = new LoggingEvent();
        event.setLoggerContext(context);
        event.setLoggerName("com.interviewengine.session.service.InterviewRoomService");
        event.setLevel(Level.INFO);
        event.setMessage("interview started");
        event.setThreadName("http-nio-8080-exec-1");
        event.setTimeStamp(System.currentTimeMillis());
        event.setMDCPropertyMap(MDC.getCopyOfContextMap() == null ? Map.of() : MDC.getCopyOfContextMap());

        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }
}
