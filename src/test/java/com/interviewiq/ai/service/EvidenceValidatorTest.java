package com.interviewiq.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence validation before a report is persisted (PRD v2.1 §7.6).
 *
 * <p>"A report whose narrative does not cite answers is a defect, not a stylistic
 * preference." These tests hold that line.
 */
class EvidenceValidatorTest {

    private final EvidenceValidator validator = new EvidenceValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final String COMPLETE = """
            {
              "overall_score": 72,
              "summary": "A solid mid-level backend engineer with real production experience. Communication is clear but occasionally unstructured. Recommended for a technical round.",
              "dimensions": {
                "TECHNICAL":       { "narrative": "Demonstrated real depth on connection pooling and indexing, citing a specific incident.", "citedAnswerIndexes": [0, 2] },
                "COMMUNICATION":   { "narrative": "Answers were clear though the second one wandered before reaching the point.", "citedAnswerIndexes": [1] },
                "RELEVANCE":       { "narrative": "Stayed on topic throughout and addressed what was actually asked each time.", "citedAnswerIndexes": [0, 1] },
                "PROBLEM_SOLVING": { "narrative": "Worked from symptom to cause methodically when describing the outage.", "citedAnswerIndexes": [2] }
              },
              "perQuestion": [
                { "questionIndex": 0, "score": 8, "narrative": "Named the exact tools and gave measured outcomes." },
                { "questionIndex": 1, "score": 6, "narrative": "Answered, but took a while to reach the point." },
                { "questionIndex": 2, "score": 7, "narrative": "Traced the incident clearly from alert to fix." }
              ]
            }
            """;

    @Test
    void acceptsAReportThatCitesItsEvidence() {
        assertThat(validator.validate(parse(COMPLETE), 3)).isEmpty();
        assertThat(validator.isAcceptable(parse(COMPLETE), 3)).isTrue();
    }

    // =========================================================================
    // The defect §7.6 names: a claim with no citation
    // =========================================================================

    @Test
    void rejectsADimensionThatCitesNoAnswer() {
        String json = COMPLETE.replace("\"citedAnswerIndexes\": [0, 2]", "\"citedAnswerIndexes\": []");

        assertThat(validator.validate(parse(json), 3))
                .anyMatch(p -> p.contains("TECHNICAL") && p.contains("cites no answer"));
    }

    @Test
    void rejectsACitationPointingAtAnAnswerThatDoesNotExist() {
        // The model invented a citation. A recruiter following it would find
        // nothing, which is worse than no citation at all.
        String json = COMPLETE.replace("\"citedAnswerIndexes\": [1]", "\"citedAnswerIndexes\": [17]");

        assertThat(validator.validate(parse(json), 3))
                .anyMatch(p -> p.contains("does not exist"));
    }

    @Test
    void rejectsABareScoreWithNoNarrativeAtAll() {
        JsonNode bare = parse("""
                { "overall_score": 72, "technical_score": 7, "recommendation": "HIRE" }
                """);

        // This is precisely the output §7.6 forbids: "A bare score is never
        // acceptable output."
        assertThat(validator.validate(bare, 3)).isNotEmpty();
        assertThat(validator.isAcceptable(bare, 3)).isFalse();
    }

    @Test
    void rejectsAMissingDimension() {
        String json = COMPLETE.replace("\"PROBLEM_SOLVING\"", "\"UNUSED_DIMENSION\"");

        assertThat(validator.validate(parse(json), 3))
                .anyMatch(p -> p.contains("PROBLEM_SOLVING"));
    }

    @Test
    void rejectsANarrativeTooShortToBeEvidence() {
        String json = COMPLETE.replace(
                "\"narrative\": \"Answers were clear though the second one wandered before reaching the point.\"",
                "\"narrative\": \"Good.\"");

        assertThat(validator.validate(parse(json), 3))
                .anyMatch(p -> p.contains("too short"));
    }

    @Test
    void rejectsAMissingOverallSummary() {
        String json = COMPLETE.replaceAll("\"summary\":[^\"]*\"[^\"]*\"", "\"summary\": \"\"");

        assertThat(validator.validate(parse(json), 3))
                .anyMatch(p -> p.contains("summary"));
    }

    // =========================================================================
    // Per-question coverage
    // =========================================================================

    @Test
    void rejectsAbsentPerQuestionNarrative() {
        String json = COMPLETE.replaceAll("(?s)\"perQuestion\": \\[.*?\\]", "\"perQuestion\": []");

        assertThat(validator.validate(parse(json), 3))
                .anyMatch(p -> p.contains("per-question"));
    }

    @Test
    void rejectsAQuestionScoredWithoutExplanation() {
        String json = COMPLETE.replace(
                "\"narrative\": \"Named the exact tools and gave measured outcomes.\"",
                "\"narrative\": \"\"");

        assertThat(validator.validate(parse(json), 3))
                .anyMatch(p -> p.contains("no narrative"));
    }

    @Test
    void rejectsPerQuestionNarrativeForAQuestionNeverAsked() {
        String json = COMPLETE.replace("\"questionIndex\": 2", "\"questionIndex\": 9");

        assertThat(validator.validate(parse(json), 3))
                .anyMatch(p -> p.contains("does not exist"));
    }

    // =========================================================================
    // Partial interviews
    // =========================================================================

    @Test
    void validatesAgainstTheAnswersActuallyGiven() {
        // A candidate who dropped off after 2 of 3 questions: citing index 2 is
        // now out of range, because that answer does not exist (§7.5.7).
        assertThat(validator.validate(parse(COMPLETE), 2))
                .anyMatch(p -> p.contains("does not exist"));
    }
}
