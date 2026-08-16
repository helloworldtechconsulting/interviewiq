package com.interviewengine.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.interviewengine.job.domain.JobOpening;
import com.interviewengine.shared.exception.AiServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stage 1 of two-stage question generation: the per-opening question bank
 * (PRD v2.1 §7.5, INTIQ-17).
 *
 * <p>Generated once when a job description finishes extracting, not once per
 * candidate. Everything a candidate is asked comes from here except their
 * resume-anchored questions, which is what makes 25 candidates for the same
 * opening scorable against each other.
 *
 * <h2>The safety filter runs here, and it did not before</h2>
 *
 * <p>{@link QuestionSafetyFilter} was wired into employer-supplied questions and
 * into live follow-ups, but <strong>not</strong> into AI-generated interview
 * questions — the exact case INTIQ-93 built it for. The prompt asks the model to
 * avoid protected attributes, and a prompt instruction is not a control: it is a
 * request to the same system whose output you are trying to constrain.
 *
 * <p>Every generated question is now screened before it is persisted, and drops
 * are logged with the question text and the rule that caught them. That log is
 * the audit trail the PRD's HIGH-severity bias risk requires, and it is worth
 * nothing if the main generation path never writes to it.
 *
 * <h2>Core selection is automatic and reproducible</h2>
 *
 * <p>Recruiters never review questions (INTIQ-93), so the comparability core is
 * chosen by the system: highest-ranked Technical and Scenario questions, with a
 * deterministic tie-break on question id. Reproducible matters — a core that
 * shifts between runs would mean two candidates interviewed minutes apart were
 * scored on different fixed sets, which is the thing the core exists to prevent.
 */
@Service
public class QuestionBankService {

    private static final Logger log = LoggerFactory.getLogger(QuestionBankService.class);

    /**
     * Target bank size. Large enough that the rotating tail is genuinely varied
     * across a 25-candidate opening, small enough for one generation call.
     */
    static final int BANK_TARGET = 50;

    /** Below this the bank cannot support a rotating tail and the job is failed rather than shipped thin. */
    static final int BANK_MINIMUM = 20;

    /** Size of the comparability core (§7.5). */
    static final int CORE_SIZE = 6;

    /** Categories the core is drawn from — the ones that actually discriminate on capability. */
    private static final Set<String> CORE_CATEGORIES = Set.of("TECHNICAL", "SCENARIO");

    private final ChatClient chatClient;
    private final PromptTemplateService prompts;
    private final QuestionSafetyFilter safetyFilter;
    private final ObjectMapper objectMapper;

    public QuestionBankService(@Qualifier("questionChatClient") ChatClient chatClient,
                               PromptTemplateService prompts,
                               QuestionSafetyFilter safetyFilter,
                               ObjectMapper objectMapper) {
        this.chatClient   = chatClient;
        this.prompts      = prompts;
        this.safetyFilter = safetyFilter;
        this.objectMapper = objectMapper;
    }

    /**
     * Generates, screens and structures the bank for one opening.
     *
     * @param job                the opening, which must have extracted JD text
     * @param employerQuestions  the employer's own questions, so the model does
     *                           not generate near-duplicates of questions that
     *                           are already going to be asked (§7.5.8)
     * @return the bank as JSON: {@code {"questions":[...],"coreQuestionIds":[...]}}
     * @throws AiServiceException if too few questions survive screening
     */
    public String generate(JobOpening job, List<String> employerQuestions) {
        String prompt = prompts.render(PromptTemplateService.QUESTION_GENERATION, Map.of(
                "jdText", job.getJdText() == null ? "" : job.getJdText(),
                "questionCount", BANK_TARGET,
                "durationMinutes", job.getDurationTier().getMinutes(),
                "employerQuestions", employerQuestions,
                // No resume at bank stage — the bank is per-opening, not per
                // person, and the template branches on this being absent.
                "resumeText", "",
                "candidateRef", "the candidate"));

        String raw = chatClient.prompt().user(prompt).call().content();
        ArrayNode generated = parseArray(raw, job.getId().toString());

        List<ObjectNode> accepted = screen(generated, job);

        if (accepted.size() < BANK_MINIMUM) {
            // Failing loudly beats shipping a bank too thin to rotate. A
            // 12-question bank on a 15-question interview means every candidate
            // gets nearly the same set, which silently defeats the anti-leak
            // property this whole design exists for.
            throw new AiServiceException(
                    "Question bank for job " + job.getId() + " has only " + accepted.size()
                            + " usable questions after screening; minimum is " + BANK_MINIMUM + ".");
        }

        return assemble(accepted);
    }

    // =========================================================================
    // Screening
    // =========================================================================

    /**
     * Drops questions that fail the prohibited-topic filter or that duplicate
     * one already accepted, and logs every drop.
     */
    private List<ObjectNode> screen(ArrayNode generated, JobOpening job) {
        List<ObjectNode> accepted = new ArrayList<>(generated.size());
        Set<String> seen = new LinkedHashSet<>();
        int refused = 0;
        int duplicates = 0;

        for (JsonNode node : generated) {
            String text = node.path("text").asText("").strip();
            if (text.isEmpty()) {
                continue;
            }

            QuestionSafetyFilter.Verdict verdict = safetyFilter.screen(text);
            if (!verdict.approved()) {
                refused++;
                // Logged with the text and the category, because this is the
                // evidence trail if a candidate ever challenges a question.
                log.warn("Question bank: filter dropped a generated question. jobId={} category={} text={}",
                        job.getId(), verdict.prohibitedCategory(), text);
                continue;
            }

            // Near-duplicate detection on normalised text. The model returns
            // duplicates more often than one would expect at 50 questions, and
            // two identical questions in a bank means one wasted slot per
            // candidate who draws both.
            if (!seen.add(normalise(text))) {
                duplicates++;
                continue;
            }

            accepted.add(toQuestion(node, text, accepted.size()));
        }

        log.info("Question bank screened: jobId={} generated={} accepted={} refused={} duplicates={}",
                job.getId(), generated.size(), accepted.size(), refused, duplicates);

        return accepted;
    }

    /** Lowercase, strip punctuation and collapse whitespace, for duplicate comparison only. */
    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").strip();
    }

    private ObjectNode toQuestion(JsonNode source, String text, int index) {
        ObjectNode q = objectMapper.createObjectNode();
        // Stable within the bank and independent of ordering, so telemetry and
        // core selection can both refer to a question by id.
        q.put("id", "q" + index);
        q.put("text", text);
        q.put("category", upper(source.path("category").asText("TECHNICAL")));
        q.put("dimension", upper(source.path("dimension").asText("TECHNICAL")));
        q.put("rationale", source.path("rationale").asText(""));
        q.put("rank", index);
        return q;
    }

    private static String upper(String s) {
        return s == null ? "" : s.strip().toUpperCase(Locale.ROOT);
    }

    // =========================================================================
    // Assembly
    // =========================================================================

    /**
     * Wraps the accepted questions with the automatically selected core.
     *
     * <p>Core selection prefers Technical and Scenario questions in the model's
     * own ranking order, then falls back to whatever remains if the bank is
     * unusually light on them — a core of four is worse than a core of six, but
     * a job that cannot be interviewed at all is worse than both.
     */
    private String assemble(List<ObjectNode> accepted) {
        List<String> core = accepted.stream()
                .filter(q -> CORE_CATEGORIES.contains(q.get("category").asText()))
                .sorted(Comparator.comparingInt(q -> q.get("rank").asInt()))
                .limit(CORE_SIZE)
                .map(q -> q.get("id").asText())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        if (core.size() < CORE_SIZE) {
            for (ObjectNode q : accepted) {
                String id = q.get("id").asText();
                if (!core.contains(id)) {
                    core.add(id);
                }
                if (core.size() == CORE_SIZE) {
                    break;
                }
            }
        }

        ObjectNode bank = objectMapper.createObjectNode();
        ArrayNode questions = bank.putArray("questions");
        accepted.forEach(questions::add);
        ArrayNode coreIds = bank.putArray("coreQuestionIds");
        core.forEach(coreIds::add);

        return bank.toString();
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    private ArrayNode parseArray(String raw, String jobIdForLog) {
        if (raw == null || raw.isBlank()) {
            throw new AiServiceException("Question generation returned nothing for job " + jobIdForLog);
        }
        String cleaned = stripFences(raw);
        try {
            JsonNode tree = objectMapper.readTree(cleaned);
            if (!tree.isArray()) {
                throw new AiServiceException(
                        "Question generation returned " + tree.getNodeType()
                                + " rather than an array for job " + jobIdForLog);
            }
            return (ArrayNode) tree;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AiServiceException(
                    "Question generation returned unparseable JSON for job " + jobIdForLog, e);
        }
    }

    /** Models wrap JSON in markdown fences despite being told not to; strip them rather than fail. */
    private static String stripFences(String content) {
        String trimmed = content.strip();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline > 0 && lastFence > firstNewline) {
            return trimmed.substring(firstNewline + 1, lastFence).strip();
        }
        return trimmed;
    }
}
