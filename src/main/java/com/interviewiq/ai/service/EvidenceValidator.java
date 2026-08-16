package com.interviewiq.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks that an evaluation actually cites its evidence, before the report is
 * persisted (PRD v2.1 §7.6).
 *
 * <p>The PRD leaves no room here:
 *
 * <blockquote>
 * "Every evaluation report carries per-question narrative evidence, and every
 * claim cites a specific answer — never a bare score. ... This is a hard output
 * requirement on the evaluation prompt, <strong>validated before the report is
 * persisted — a report whose narrative does not cite answers is a defect, not a
 * stylistic preference.</strong>"
 * </blockquote>
 *
 * <p>The commercial reasoning is what makes this worth failing a report over: "a
 * recruiter who can see <em>why</em> the score is 72 will trust and act on it; a
 * bare '72' gets ignored. The quoted evidence is also the best defence if a
 * candidate ever challenges a decision."
 *
 * <p>A failed validation is treated as a retryable LLM failure. The prompt asks
 * for citations explicitly, so a response without them is a model that did not
 * comply — and the right response is another attempt, not a silently degraded
 * report shown to a recruiter as though it were complete.
 *
 * <p>Note this is a structural check, and deliberately so. A database CHECK
 * cannot answer "does this citation point at an answer that exists", but neither
 * can any automated check answer "is this narrative <em>true</em>". What it can
 * establish is that every dimension has a narrative, that narratives reference
 * real answer indexes, and that per-question coverage exists — which is the
 * difference between a report and a score.
 */
@Service
public class EvidenceValidator {

    private static final Logger log = LoggerFactory.getLogger(EvidenceValidator.class);

    private static final List<String> REQUIRED_DIMENSIONS =
            List.of("TECHNICAL", "COMMUNICATION", "RELEVANCE", "PROBLEM_SOLVING");

    /** A dimension narrative shorter than this is not 2-3 sentences of anything. */
    private static final int MIN_NARRATIVE_CHARS = 40;

    /**
     * Validates the evidence in a parsed evaluation response.
     *
     * @param evaluation   the model's parsed JSON
     * @param answerCount  how many answers the interview actually produced, so
     *                     citations can be checked against real indexes
     * @return the problems found; empty means the evidence is acceptable
     */
    public List<String> validate(JsonNode evaluation, int answerCount) {
        List<String> problems = new ArrayList<>();

        JsonNode dimensions = evaluation.path("dimensions");
        if (dimensions.isMissingNode() || !dimensions.isObject()) {
            problems.add("no per-dimension narrative was produced");
        } else {
            for (String dimension : REQUIRED_DIMENSIONS) {
                validateDimension(dimensions.path(dimension), dimension, answerCount, problems);
            }
        }

        String summary = evaluation.path("summary").asText("");
        if (summary.isBlank()) {
            problems.add("no overall summary was produced");
        }

        validatePerQuestion(evaluation.path("perQuestion"), answerCount, problems);

        if (!problems.isEmpty()) {
            log.warn("Evaluation evidence rejected: {}", String.join("; ", problems));
        }
        return problems;
    }

    private void validateDimension(JsonNode dimension,
                                   String name,
                                   int answerCount,
                                   List<String> problems) {
        if (dimension.isMissingNode() || !dimension.isObject()) {
            problems.add("dimension " + name + " has no narrative");
            return;
        }

        String narrative = dimension.path("narrative").asText("");
        if (narrative.isBlank()) {
            problems.add("dimension " + name + " has no narrative");
            return;
        }
        if (narrative.length() < MIN_NARRATIVE_CHARS) {
            problems.add("dimension " + name + " narrative is too short to be evidence");
        }

        JsonNode cited = dimension.path("citedAnswerIndexes");
        if (!cited.isArray() || cited.isEmpty()) {
            // This is the specific defect §7.6 names: a claim with no citation.
            problems.add("dimension " + name + " cites no answer");
            return;
        }
        for (JsonNode index : cited) {
            int value = index.asInt(-1);
            if (value < 0 || value >= answerCount) {
                problems.add("dimension " + name + " cites answer " + index.asText()
                        + ", which does not exist in this interview");
            }
        }
    }

    private void validatePerQuestion(JsonNode perQuestion, int answerCount, List<String> problems) {
        if (!perQuestion.isArray() || perQuestion.isEmpty()) {
            problems.add("no per-question narrative was produced");
            return;
        }

        for (JsonNode entry : perQuestion) {
            int index = entry.path("questionIndex").asInt(-1);
            if (index < 0 || index >= answerCount) {
                problems.add("per-question narrative references question " + index
                        + ", which does not exist in this interview");
            }
            if (entry.path("narrative").asText("").isBlank()) {
                problems.add("question " + index + " has a score but no narrative");
            }
        }
    }

    /** Convenience for the common case. */
    public boolean isAcceptable(JsonNode evaluation, int answerCount) {
        return validate(evaluation, answerCount).isEmpty();
    }
}
