package com.interviewiq.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewiq.job.domain.DurationTier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link QuestionAssemblyService} — stage 2 of INTIQ-17.
 *
 * <p>The three properties asserted here are the ones the whole two-stage design
 * exists to provide, and each fails silently if broken: comparability (every
 * candidate gets the same core), variety (sets differ), and reproducibility (the
 * same candidate always gets the same set).
 */
class QuestionAssemblyServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final QuestionAssemblyService service = new QuestionAssemblyService(mapper);

    /** A bank of 50 questions with the first six marked core. */
    private String bank() {
        StringBuilder sb = new StringBuilder("{\"questions\":[");
        for (int i = 0; i < 50; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"q").append(i)
              .append("\",\"text\":\"Question ").append(i)
              .append("\",\"category\":\"TECHNICAL\",\"dimension\":\"TECHNICAL\",\"rank\":").append(i)
              .append('}');
        }
        sb.append("],\"coreQuestionIds\":[\"q0\",\"q1\",\"q2\",\"q3\",\"q4\",\"q5\"]}");
        return sb.toString();
    }

    private List<String> texts(String json) throws Exception {
        JsonNode tree = mapper.readTree(json);
        List<String> out = new ArrayList<>();
        tree.forEach(q -> out.add(q.path("text").asText()));
        return out;
    }

    // =========================================================================
    // Comparability
    // =========================================================================

    /**
     * The core is the reason two candidates for one opening can be ranked
     * against each other at all. If it drifted between candidates the scores
     * would still look comparable and quietly would not be.
     */
    @Test
    void everyCandidateGetsTheSameCoreQuestions() throws Exception {
        String a = service.assemble(bank(), List.of(), List.of(), DurationTier.STANDARD, UUID.randomUUID());
        String b = service.assemble(bank(), List.of(), List.of(), DurationTier.STANDARD, UUID.randomUUID());

        Set<String> coreTexts = Set.of("Question 0", "Question 1", "Question 2",
                "Question 3", "Question 4", "Question 5");

        assertThat(texts(a)).containsAll(coreTexts);
        assertThat(texts(b)).containsAll(coreTexts);
    }

    /** §7.5.8 — employer questions occupy the core segment first. */
    @Test
    void employerQuestionsComeFirstAndAreMarkedAsTheirs() throws Exception {
        String json = service.assemble(bank(),
                List.of("Why do you want to work here?"), List.of(),
                DurationTier.STANDARD, UUID.randomUUID());

        JsonNode first = mapper.readTree(json).get(0);
        assertThat(first.path("text").asText()).isEqualTo("Why do you want to work here?");
        assertThat(first.path("source").asText()).isEqualTo("EMPLOYER");
    }

    // =========================================================================
    // Variety and anti-leak
    // =========================================================================

    /**
     * Candidates for one role compare notes within hours. A rotating tail is
     * what stops the third candidate preparing fully from what the first posted.
     */
    @Test
    void differentCandidatesGetDifferentRotatingQuestions() throws Exception {
        List<String> a = texts(service.assemble(bank(), List.of(), List.of(),
                DurationTier.STANDARD, UUID.randomUUID()));
        List<String> b = texts(service.assemble(bank(), List.of(), List.of(),
                DurationTier.STANDARD, UUID.randomUUID()));

        assertThat(a).isNotEqualTo(b);

        // Overlap should be substantial (the shared core) but not total.
        Set<String> shared = new HashSet<>(a);
        shared.retainAll(b);
        assertThat(shared).hasSizeGreaterThanOrEqualTo(6);
        assertThat(shared.size()).isLessThan(a.size());
    }

    // =========================================================================
    // Reproducibility
    // =========================================================================

    /**
     * Seeded on the candidate id, so a retry after a transient failure hands the
     * candidate the same interview rather than a different one — and "why did
     * this candidate get that question" has a reproducible answer.
     */
    @Test
    void theSameCandidateAlwaysGetsTheSameSet() throws Exception {
        UUID candidateId = UUID.randomUUID();

        String first  = service.assemble(bank(), List.of(), List.of(), DurationTier.STANDARD, candidateId);
        String second = service.assemble(bank(), List.of(), List.of(), DurationTier.STANDARD, candidateId);

        assertThat(first).isEqualTo(second);
    }

    // =========================================================================
    // Sizing and shape
    // =========================================================================

    @Test
    void theSetSizeFollowsTheDurationTier() throws Exception {
        for (DurationTier tier : DurationTier.values()) {
            String json = service.assemble(bank(), List.of(), List.of(), tier, UUID.randomUUID());
            assertThat(texts(json))
                    .as("tier %s should produce %d questions", tier, tier.getQuestionCount())
                    .hasSize(tier.getQuestionCount());
        }
    }

    @Test
    void everyQuestionIsNumberedInOrder() throws Exception {
        JsonNode tree = mapper.readTree(
                service.assemble(bank(), List.of(), List.of(), DurationTier.STANDARD, UUID.randomUUID()));

        for (int i = 0; i < tree.size(); i++) {
            assertThat(tree.get(i).path("order").asInt()).isEqualTo(i + 1);
        }
    }

    /**
     * Résumé questions are spread through the set rather than clustered. Three
     * consecutive questions about the candidate's own history reads as a
     * different interview inside the interview, and skews the difficulty curve.
     */
    @Test
    void resumeQuestionsAreSpreadRatherThanClustered() throws Exception {
        String json = service.assemble(bank(), List.of(),
                List.of("Tell me about the payments project.",
                        "Why did you move from QA to backend?",
                        "What went wrong on the migration?"),
                DurationTier.STANDARD, UUID.randomUUID());

        JsonNode tree = mapper.readTree(json);
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < tree.size(); i++) {
            if ("RESUME".equals(tree.get(i).path("segment").asText())) {
                positions.add(i);
            }
        }

        assertThat(positions).isNotEmpty();
        // Never the opening question — the interview should start on the role.
        assertThat(positions.get(0)).isGreaterThan(0);
        for (int i = 1; i < positions.size(); i++) {
            assertThat(positions.get(i) - positions.get(i - 1))
                    .as("resume questions should not be adjacent")
                    .isGreaterThan(1);
        }
    }

    /**
     * The no-résumé path is the common case on a bulk import, not an edge case —
     * it must produce a full-length interview from the bank alone.
     */
    @Test
    void aCandidateWithNoResumeStillGetsAFullInterview() throws Exception {
        String json = service.assemble(bank(), List.of(), List.of(),
                DurationTier.COMPREHENSIVE, UUID.randomUUID());

        assertThat(texts(json)).hasSize(DurationTier.COMPREHENSIVE.getQuestionCount());
    }

    /** Bank questions carry their id through, so telemetry can attribute outcomes back. */
    @Test
    void bankQuestionsCarryTheirIdForTelemetry() throws Exception {
        JsonNode tree = mapper.readTree(
                service.assemble(bank(), List.of(), List.of(), DurationTier.STANDARD, UUID.randomUUID()));

        boolean anyBankQuestion = false;
        for (JsonNode q : tree) {
            if ("AI".equals(q.path("source").asText()) && q.has("bankQuestionId")) {
                anyBankQuestion = true;
                assertThat(q.path("bankQuestionId").asText()).isNotBlank();
            }
        }
        assertThat(anyBankQuestion).isTrue();
    }
}
