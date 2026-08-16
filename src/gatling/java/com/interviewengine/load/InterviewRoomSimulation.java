package com.interviewengine.load;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Load test for the interview room (INTIQ-99, PRD v2.1 §8).
 *
 * <h2>The number this exists to measure</h2>
 *
 * <p>Architecture v4.0 §0 sets {@code interviews-per-task} to 15 and says
 * plainly that the figure is "set conservatively, to be measured in the Phase 9
 * load test". Until it is measured, two things rest on a guess: the HPA
 * thresholds that decide when to add a web pod, and the claim that 25
 * concurrent interviews fit comfortably on two pods.
 *
 * <p>The guess is probably right — §0 argues a live interview costs a pod
 * roughly 50–100 KB of session state and about one answer-submit per 60–90
 * seconds, because all media stays in the browser. This simulation is what
 * turns that argument into a measurement.
 *
 * <h2>What it simulates</h2>
 *
 * <p>One virtual user is one candidate taking a full interview over WebSocket:
 * connect, {@code session.start}, then answer questions with realistic think
 * time, then {@code session.end}. The timings matter more than the volume — a
 * load test that submits answers back-to-back measures a throughput ceiling
 * nobody will ever hit, and tells you nothing about the socket-heavy,
 * mostly-idle shape of the real workload. Idle sockets are the actual cost
 * here, and they only show up if the virtual users sit idle like real ones.
 *
 * <h2>What to read afterwards</h2>
 *
 * <ol>
 *   <li>Heap per pod against the ~50–100 KB per interview §0 predicts. This is
 *       the figure that sets the real ceiling.</li>
 *   <li>{@code hikaricp_connections_pending} — must stay at zero. Anything
 *       above it means the pool sizing in Arch §5.4 is wrong.</li>
 *   <li>WebSocket disconnects. Any at all deserve investigation: §7.5.2 calls
 *       killing a live interview "the single worst bug this product could
 *       ship".</li>
 * </ol>
 *
 * <h2>Running it</h2>
 *
 * <p>Needs seeded sessions — see {@code src/gatling/resources/load/README.md}.
 * Point it at staging, never production: it completes real interviews, and
 * against production it would consume real wallet balance and generate real
 * evaluation reports for candidates who do not exist.
 */
public class InterviewRoomSimulation extends Simulation {

    // ── Tunables ────────────────────────────────────────────────────────────

    private static final String BASE_URL =
            System.getProperty("load.baseUrl", "http://localhost:8080");

    private static final String WS_URL =
            BASE_URL.replaceFirst("^http", "ws") + "/ws/interview";

    /** Target concurrency. 25 is the launch figure Arch v4.0 §0 works through. */
    private static final int CONCURRENT_INTERVIEWS =
            Integer.getInteger("load.concurrentInterviews", 25);

    /**
     * Ramp duration. Long enough that pods are not all cold-starting at once —
     * a spike of simultaneous JVM warmups measures class loading, not steady
     * state.
     */
    private static final Duration RAMP = Duration.ofMinutes(
            Integer.getInteger("load.rampMinutes", 5));

    /** Questions per interview. 15 is the STANDARD tier (§7.2.1). */
    private static final int QUESTIONS =
            Integer.getInteger("load.questions", 15);

    // ── Protocol ────────────────────────────────────────────────────────────

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .wsBaseUrl(WS_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("InterviewEngine-LoadTest/1.0")
            // Real candidates arrive on separate connections. Sharing one would
            // hide connection-level limits, which is part of what is under test.
            .shareConnections();

    /**
     * Invite tokens, one per row, from a CSV the seeding script writes.
     *
     * <p>{@code queue} rather than {@code random}: every token is a
     * single-use session, and handing the same one to two virtual users would
     * produce a conflict that looks like a server error in the report.
     */
    private final FeederBuilder<String> tokens =
            csv("load/session-tokens.csv").queue();

    // ── The interview ───────────────────────────────────────────────────────

    private final ChainBuilder answerQuestions =
            repeat(QUESTIONS, "q").on(
                    // A candidate thinking about and then speaking an answer.
                    // §0 models this at one submit per 60-90 seconds; the
                    // spread matters, because a fixed interval would
                    // synchronise every virtual user onto the same tick and
                    // create a load pattern no real cohort produces.
                    pause(Duration.ofSeconds(45), Duration.ofSeconds(90))
                            .exec(
                                    ws("answer.submit")
                                            .sendText("""
                                                    {"event":"answer.submit",
                                                     "questionIndex":#{q},
                                                     "transcriptText":"This is a load-test answer for question #{q}. \
                                                     It is deliberately several sentences long so the payload \
                                                     resembles a real spoken response rather than a token.",
                                                     "durationSeconds":75}""")
                                            .await(Duration.ofSeconds(30))
                                            .on(ws.checkTextMessage("ack-or-next")
                                                    .check(jsonPath("$.event")
                                                            .in("ack", "question.next", "followup.question")))
                            )
            );

    private final ScenarioBuilder interview = scenario("Candidate interview")
            .feed(tokens)

            .exec(http("GET /candidate/interview/init")
                    .get("/api/v1/candidate/interview/init")
                    .header("Authorization", "Bearer #{inviteToken}")
                    .check(status().is(200)))

            .exec(ws("Connect").connect("?token=#{inviteToken}")
                    .await(Duration.ofSeconds(30))
                    .on(ws.checkTextMessage("connected")
                            .check(jsonPath("$.event").exists())))

            .exec(ws("session.start")
                    .sendText("{\"event\":\"session.start\"}")
                    .await(Duration.ofSeconds(30))
                    .on(ws.checkTextMessage("first-question")
                            .check(jsonPath("$.event").is("question.next"))))

            .exec(answerQuestions)

            .exec(ws("session.end")
                    .sendText("{\"event\":\"session.end\"}")
                    .await(Duration.ofSeconds(30))
                    .on(ws.checkTextMessage("terminated")
                            .check(jsonPath("$.event").is("session.terminated"))))

            .exec(ws("Close").close());

    {
        setUp(
                interview.injectClosed(
                        // Closed model, not open. The question is "what does N
                        // concurrent interviews cost?", and a closed model holds
                        // concurrency at N — an open model would keep adding
                        // arrivals regardless of whether the system was keeping
                        // up, which measures the breaking point instead.
                        rampConcurrentUsers(0).to(CONCURRENT_INTERVIEWS).during(RAMP),
                        constantConcurrentUsers(CONCURRENT_INTERVIEWS)
                                .during(Duration.ofMinutes(
                                        Integer.getInteger("load.holdMinutes", 20)))
                )
        ).protocols(httpProtocol)
                .assertions(
                        // A failed request here is a candidate whose interview
                        // broke. There is no acceptable background rate.
                        global().failedRequests().percent().lt(1.0),

                        // The room is a text relay writing to Postgres (§0).
                        // Anything approaching a second means something is
                        // wrong — most likely connection-pool contention.
                        details("answer.submit").responseTime().percentile3().lt(1000),

                        // §7.5.1: the interview must start in under 5 seconds.
                        details("session.start").responseTime().percentile3().lt(5000)
                );
    }
}
