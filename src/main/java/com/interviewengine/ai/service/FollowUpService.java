package com.interviewengine.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewengine.ai.config.AiConfig;
import com.interviewengine.candidate.domain.Candidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Decides whether an answer warrants one follow-up question (PRD v2.1 §7.5.2, step 9).
 *
 * <p>"The backend persists the answer, then asks the configured
 * {@code app.ai.followup} model whether the answer warrants a follow-up. If it
 * does, {@code followup.question} is pushed; otherwise the next question from the
 * bank is pushed as {@code question.next}."
 *
 * <h2>This call is on the critical path</h2>
 *
 * <p>Unlike question generation and evaluation, this runs <em>while a candidate
 * is sitting in silence waiting for the next question</em>. Everything about it
 * is shaped by that:
 *
 * <ul>
 *   <li>It uses the cheapest, fastest tier — §9 selects GPT-5.4-nano for
 *       "trivial real-time classification, latency-sensitive".</li>
 *   <li>It has a hard timeout, and a timeout means "no follow-up" rather than an
 *       error. A candidate waiting on a dead API call is a worse outcome than a
 *       missed follow-up.</li>
 *   <li>It never throws. Every failure path returns
 *       {@link Decision#none()} and the interview moves on.</li>
 * </ul>
 *
 * <p>The prompt itself defaults to no follow-up, because the interview has a
 * fixed question budget and a hard timer — every follow-up displaces a planned
 * question.
 */
@Service
public class FollowUpService {

    private static final Logger log = LoggerFactory.getLogger(FollowUpService.class);

    /**
     * How long the candidate may be left waiting on this decision.
     *
     * <p>Generous for a nano-tier classification and still short enough that a
     * stalled provider does not become a silent gap in the conversation.
     */
    private static final java.time.Duration TIMEOUT = java.time.Duration.ofSeconds(6);

    private final ChatClient followupClient;
    private final PromptTemplateService prompts;
    private final PiiRedactionService redaction;
    private final QuestionSafetyFilter safetyFilter;
    private final ObjectMapper objectMapper;

    public FollowUpService(@Qualifier(AiConfig.FOLLOWUP_CLIENT) ChatClient followupClient,
                           PromptTemplateService prompts,
                           PiiRedactionService redaction,
                           QuestionSafetyFilter safetyFilter,
                           ObjectMapper objectMapper) {
        this.followupClient = followupClient;
        this.prompts        = prompts;
        this.redaction      = redaction;
        this.safetyFilter   = safetyFilter;
        this.objectMapper   = objectMapper;
    }

    /**
     * Asks whether this answer deserves a probe.
     *
     * @param candidate used for redaction; may be null
     * @return the follow-up to ask, or {@link Decision#none()}
     */
    public Decision decide(String questionText, String answerText, Candidate candidate) {
        // A skipped or empty answer never warrants a follow-up. Probing someone
        // who just failed to answer is uncomfortable and produces nothing — and
        // it saves a call on the critical path.
        if (answerText == null || answerText.isBlank()) {
            return Decision.none();
        }

        try {
            String prompt = prompts.render(PromptTemplateService.FOLLOWUP, Map.of(
                    "questionText", redaction.redact(questionText, candidate),
                    "answerText", redaction.redact(answerText, candidate)));

            String raw = java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> followupClient.prompt().user(prompt).call().content())
                    .orTimeout(TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                    .join();

            return parse(raw);

        } catch (Exception e) {
            // Never propagate. The candidate is mid-interview and the next bank
            // question is a perfectly good outcome.
            log.warn("Follow-up decision failed, continuing without one: {}", e.getMessage());
            return Decision.none();
        }
    }

    private Decision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Decision.none();
        }
        try {
            JsonNode node = objectMapper.readTree(stripFences(raw));
            if (!node.path("followUp").asBoolean(false)) {
                return Decision.none();
            }

            String question = node.path("question").asText("").strip();
            if (question.isBlank()) {
                return Decision.none();
            }

            // The safety filter applies to generated follow-ups exactly as it
            // does to the bank. A model that drifts into a prohibited topic
            // mid-interview is the worst place to discover the filter was only
            // wired to the upload path.
            QuestionSafetyFilter.Verdict verdict = safetyFilter.screen(question);
            if (!verdict.approved()) {
                log.warn("Follow-up refused by the safety filter: category={}",
                        verdict.prohibitedCategory());
                return Decision.none();
            }

            return new Decision(true, question);

        } catch (Exception e) {
            log.warn("Unparseable follow-up decision, continuing without one: {}", e.getMessage());
            return Decision.none();
        }
    }

    private String stripFences(String content) {
        String trimmed = content.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }

    /**
     * The outcome.
     *
     * @param shouldFollowUp whether to push {@code followup.question}
     * @param question       the follow-up text; null when there is none
     */
    public record Decision(boolean shouldFollowUp, String question) {

        public static Decision none() {
            return new Decision(false, null);
        }

        public Optional<String> questionIfAny() {
            return shouldFollowUp ? Optional.ofNullable(question) : Optional.empty();
        }
    }
}
