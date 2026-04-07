package com.interviewiq.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.shared.config.RecallProperties;
import com.interviewiq.shared.exception.BotServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client for the Recall.ai transcript API.
 *
 * <h2>Endpoint</h2>
 * <pre>GET https://api.recall.ai/api/v1/bot/{botId}/transcript/</pre>
 *
 * <h2>Authentication</h2>
 * <p>Recall.ai uses token-based authentication:
 * <pre>Authorization: Token &lt;apiKey&gt;</pre>
 *
 * <h2>Response format</h2>
 * <p>The API returns a DRF-paginated response (or a direct JSON array on some
 * API versions). This client handles both shapes:
 * <ul>
 *   <li>Paginated — {@code { "count": N, "next": "url|null", "results": [...] }}</li>
 *   <li>Direct array — {@code [ { "speaker": "...", "words": [...] } ]}</li>
 * </ul>
 *
 * <p>Each transcript segment has the shape:
 * <pre>
 * {
 *   "speaker": "Speaker 0",
 *   "words": [
 *     { "text": "Hello", "start_time": 1.0, "end_time": 1.5 },
 *     ...
 *   ]
 * }
 * </pre>
 *
 * <h2>Dev/stub mode</h2>
 * <p>When {@code app.recall.api-key} is blank (local development), the real
 * HTTP call is skipped and a hard-coded stub transcript is returned. This
 * allows the AI evaluation pipeline to be exercised end-to-end without a
 * live Recall.ai account.
 *
 * <h2>Error handling</h2>
 * <p>Any non-200 HTTP status, network error, or JSON parse failure is wrapped
 * in a {@link BotServiceException}. The evaluation worker catches this and
 * retries on the next poll cycle (up to {@code app.ai.evaluation-max-attempts}).
 */
@Component
public class RecallTranscriptClient {

    private static final Logger log = LoggerFactory.getLogger(RecallTranscriptClient.class);

    private static final String BASE_URL        = "https://api.recall.ai/api/v1";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /** Stub returned in dev/test when no API key is configured. */
    private static final String STUB_TRANSCRIPT = """
            [STUB TRANSCRIPT — Recall.ai API key not configured]
            Interviewer: Tell me about yourself.
            Candidate: I have five years of Java development experience, primarily in Spring Boot.
            Interviewer: Describe a technical challenge you solved recently.
            Candidate: I optimised a slow database query by adding a composite index and rewriting a
            nested loop in the application layer to a set-based SQL operation.
            Interviewer: How do you approach debugging a production incident?
            Candidate: I start by reproducing the error locally, then check logs, add tracing,
            and narrow down the root cause with binary search on recent deployments.
            """;

    private final RecallProperties recallProperties;
    private final ObjectMapper     objectMapper;
    private final HttpClient       httpClient;

    public RecallTranscriptClient(RecallProperties recallProperties,
                                  ObjectMapper objectMapper) {
        this.recallProperties = recallProperties;
        this.objectMapper     = objectMapper;
        this.httpClient       = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Fetches and formats the full transcript for a completed Recall.ai bot session.
     *
     * <p>Paginates automatically — follows {@code "next"} links until the
     * entire transcript has been collected.
     *
     * <p>Returns {@link #STUB_TRANSCRIPT} when {@code app.recall.api-key} is
     * blank, enabling end-to-end pipeline testing without a live Recall.ai account.
     *
     * @param botId the Recall.ai bot UUID stored on {@code interview_sessions.recall_bot_id}
     * @return plain-text transcript formatted as {@code "Speaker: words\n"} lines
     * @throws BotServiceException on HTTP error, network failure, or JSON parse failure
     */
    public String fetchTranscript(String botId) {
        String apiKey = recallProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.info("RecallTranscriptClient: api-key not configured — returning stub transcript for botId={}",
                    botId);
            return STUB_TRANSCRIPT;
        }

        log.info("RecallTranscriptClient: fetching transcript for botId={}", botId);

        try {
            List<JsonNode> allSegments = new ArrayList<>();
            String nextUrl = BASE_URL + "/bot/" + botId + "/transcript/";
            int pagesFetched = 0;

            while (nextUrl != null) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(nextUrl))
                        .header("Authorization", "Token " + apiKey)
                        .header("Accept", "application/json")
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 404) {
                    log.warn("RecallTranscriptClient: transcript not found (404) for botId={}", botId);
                    return "[TRANSCRIPT UNAVAILABLE — bot not found]";
                }

                if (response.statusCode() != 200) {
                    throw new BotServiceException(
                            "Recall.ai transcript API returned HTTP " + response.statusCode()
                            + " for botId=" + botId + " (page " + (pagesFetched + 1) + ")");
                }

                pagesFetched++;
                JsonNode root = objectMapper.readTree(response.body());
                nextUrl = collectSegments(root, allSegments);
            }

            log.debug("RecallTranscriptClient: collected {} segment(s) over {} page(s) for botId={}",
                    allSegments.size(), pagesFetched, botId);

            String formatted = formatTranscript(allSegments);
            log.info("RecallTranscriptClient: transcript ready for botId={} ({} chars)",
                    botId, formatted.length());
            return formatted;

        } catch (BotServiceException e) {
            throw e;                                             // already typed correctly
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();                  // restore interrupt flag
            throw new BotServiceException(
                    "Interrupted while fetching Recall.ai transcript for botId=" + botId, e);
        } catch (IOException e) {
            throw new BotServiceException(
                    "Network error fetching Recall.ai transcript for botId=" + botId, e);
        } catch (Exception e) {
            throw new BotServiceException(
                    "Unexpected error fetching Recall.ai transcript for botId=" + botId, e);
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Parses one HTTP response body, appends transcript segments to {@code segments},
     * and returns the {@code "next"} page URL (or {@code null} if this was the last page).
     *
     * <p>Handles both response shapes:
     * <ul>
     *   <li>Paginated — {@code { "results": [...], "next": "url|null" }}</li>
     *   <li>Direct array — {@code [ {...}, ... ]} (older API versions)</li>
     * </ul>
     */
    private String collectSegments(JsonNode root, List<JsonNode> segments) {
        if (root.isArray()) {
            // Direct array — entire transcript in one response, no pagination
            root.forEach(segments::add);
            return null;
        }

        // Paginated DRF envelope
        JsonNode results = root.path("results");
        if (results.isArray()) {
            results.forEach(segments::add);
        }

        JsonNode nextNode = root.path("next");
        if (nextNode.isNull() || nextNode.isMissingNode() || nextNode.asText().isBlank()) {
            return null;
        }
        return nextNode.asText();
    }

    /**
     * Converts a list of transcript segments into a readable plain-text format.
     *
     * <p>Each segment becomes a single line:
     * <pre>Speaker 0: The candidate said hello and mentioned their background.\n</pre>
     *
     * <p>Word-level timing data is ignored — only the {@code "text"} field of each
     * word object is concatenated (with single spaces) to reconstruct the utterance.
     */
    private String formatTranscript(List<JsonNode> segments) {
        if (segments.isEmpty()) {
            log.warn("RecallTranscriptClient: received empty transcript");
            return "[NO TRANSCRIPT AVAILABLE]";
        }

        StringBuilder sb = new StringBuilder();
        for (JsonNode segment : segments) {
            String speaker = segment.path("speaker").asText("Unknown");
            JsonNode words  = segment.path("words");

            if (words.isArray() && !words.isEmpty()) {
                StringBuilder utterance = new StringBuilder();
                for (JsonNode word : words) {
                    String text = word.path("text").asText("").strip();
                    if (!text.isEmpty()) {
                        if (!utterance.isEmpty()) utterance.append(' ');
                        utterance.append(text);
                    }
                }
                if (!utterance.isEmpty()) {
                    sb.append(speaker)
                      .append(": ")
                      .append(utterance)
                      .append('\n');
                }
            }
        }

        String result = sb.toString().strip();
        return result.isEmpty() ? "[NO TRANSCRIPT AVAILABLE]" : result;
    }
}
