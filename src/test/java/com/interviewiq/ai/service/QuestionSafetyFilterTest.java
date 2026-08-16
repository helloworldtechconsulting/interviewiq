package com.interviewiq.ai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prohibited-topic safety filter (PRD v2.1 §7.5.1, §7.5.8).
 *
 * <p>This filter applies to employer-supplied questions exactly as it does to
 * generated ones, and there is no override. §17 rates "an employer uploads a
 * discriminatory custom question" as HIGH severity.
 */
class QuestionSafetyFilterTest {

    private final QuestionSafetyFilter filter = new QuestionSafetyFilter();

    // =========================================================================
    // The categories §7.5.1 enumerates
    // =========================================================================

    @Test
    void rejectsThePrdsOwnWorkedExample() {
        // "If a customer uploads 'Are you planning to have children?', we refuse
        // it and tell them why." — §7.5.8
        var verdict = filter.screen("Are you planning to have children in the next few years?");

        assertThat(verdict.approved()).isFalse();
        assertThat(verdict.prohibitedCategory()).isEqualTo("marital and family status");
        assertThat(verdict.employerMessage()).contains("marital and family status");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "How old are you?",
            "What is your age?",
            "What is your date of birth?",
    })
    void rejectsAgeQuestions(String question) {
        assertThat(filter.screen(question).prohibitedCategory()).isEqualTo("age");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What is your gender?",
            "Are you male or female?",
    })
    void rejectsGenderQuestions(String question) {
        assertThat(filter.screen(question).prohibitedCategory()).isEqualTo("gender");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What is your religion?",
            "Are you Hindu?",
            "Which faith do you follow?",
    })
    void rejectsReligionQuestions(String question) {
        assertThat(filter.screen(question).prohibitedCategory()).isEqualTo("religion");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "What is your caste?",
            "Which caste do you belong to?",
            "Do you have a caste certificate?",
    })
    void rejectsCasteQuestions(String question) {
        assertThat(filter.screen(question).prohibitedCategory()).isEqualTo("caste");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Are you married?",
            "What is your marital status?",
            "Do you have children?",
    })
    void rejectsMaritalStatusQuestions(String question) {
        assertThat(filter.screen(question).prohibitedCategory()).isEqualTo("marital and family status");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Are you pregnant?",
            "Do you expect to take maternity leave?",
    })
    void rejectsPregnancyQuestions(String question) {
        assertThat(filter.screen(question).prohibitedCategory()).isEqualTo("pregnancy");
    }

    // =========================================================================
    // Legitimate questions must survive
    // =========================================================================

    @ParameterizedTest
    @ValueSource(strings = {
            "Describe a time you had to debug a production incident under time pressure.",
            "How would you design a rate limiter for a public API?",
            "What age group does this product target, and how did that shape your design?",
            "Tell me about a disagreement with a colleague and how you resolved it.",
            "Why are you interested in moving from consulting into a product team?",
            "Walk me through how you would diagnose a slow database query.",
            "What is your experience with Spring Boot?",
    })
    void allowsLegitimateInterviewQuestions(String question) {
        assertThat(filter.screen(question).approved())
                .as("should allow: %s", question)
                .isTrue();
    }

    @Test
    void doesNotRejectOnVocabularyAlone() {
        // The patterns target the question, not the words. A product question
        // that happens to contain "age" is a legitimate product-sense question.
        assertThat(filter.isSafe("How do you manage technical debt across a large codebase?")).isTrue();
        assertThat(filter.isSafe("What percentage of your work was greenfield?")).isTrue();
    }

    // =========================================================================
    // Contract details
    // =========================================================================

    @Test
    void anApprovedVerdictCarriesNoMessage() {
        var verdict = filter.screen("How would you approach onboarding a new team member?");

        assertThat(verdict.approved()).isTrue();
        assertThat(verdict.prohibitedCategory()).isNull();
        assertThat(verdict.employerMessage()).isNull();
    }

    @Test
    void aRejectionAlwaysNamesTheCategorySoTheEmployerCanCorrectIt() {
        var verdict = filter.screen("Are you married?");

        // §7.5.8: "The refusal names the prohibited category so the employer can
        // correct it." A generic refusal would leave them guessing.
        assertThat(verdict.employerMessage())
                .isNotNull()
                .contains("marital and family status");
    }

    @Test
    void isCaseInsensitive() {
        assertThat(filter.isSafe("ARE YOU MARRIED?")).isFalse();
        assertThat(filter.isSafe("what is YOUR Caste")).isFalse();
    }

    @Test
    void rejectsEmptyInput() {
        assertThat(filter.screen("").approved()).isFalse();
        assertThat(filter.screen("   ").approved()).isFalse();
        assertThat(filter.screen(null).approved()).isFalse();
    }
}
