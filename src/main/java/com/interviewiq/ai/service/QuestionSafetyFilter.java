package com.interviewiq.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The prohibited-topic safety filter (PRD v2.1 §7.5.1, §7.5.8).
 *
 * <p>Applied to <strong>every</strong> question, generated or employer-supplied.
 * The PRD states the rule twice and calls it non-negotiable both times:
 *
 * <blockquote>
 * "Employer questions still pass the prohibited-topic safety filter. If a
 * customer uploads 'Are you planning to have children?', we refuse it and tell
 * them why. Our platform, our liability, our reputation — the filter is not
 * optional just because a human wrote the question rather than a model."
 * </blockquote>
 *
 * <blockquote>
 * "Employer questions bypass the QUALITY critic but never the SAFETY filter. If
 * an employer wants to ask something the quality critic would score poorly, that
 * is their call and we allow it. Safety is not their call."
 * </blockquote>
 *
 * <p>There is deliberately no override. {@link Verdict} carries the prohibited
 * <em>category</em> rather than a generic refusal, because §7.5.8 requires the
 * rejection to name it so the employer can correct the question rather than
 * guess.
 *
 * <h2>Why a rule filter rather than a model call</h2>
 *
 * <p>This runs on every employer-uploaded question and on every generated batch,
 * so it must be fast, free and deterministic. More importantly it must be
 * <em>auditable</em>: "we refused your question because it asks about marital
 * status" is defensible to a customer in a way that "our classifier scored it
 * 0.71" is not. The generation prompt separately instructs the model to avoid
 * these topics; this filter is the enforcement that does not depend on the model
 * having complied.
 */
@Service
public class QuestionSafetyFilter {

    private static final Logger log = LoggerFactory.getLogger(QuestionSafetyFilter.class);

    /**
     * The prohibited categories, exactly as §7.5.1 enumerates them: "age, gender,
     * religion, caste, marital status or pregnancy". Each carries patterns that
     * indicate the question is probing that characteristic.
     *
     * <p>Patterns target the <em>question</em>, not the vocabulary. "What is your
     * age?" is prohibited; "What age group does this product target?" is a
     * legitimate product-sense question, so the patterns are anchored to
     * second-person and personal-status phrasing rather than bare keywords.
     */
    private static final List<Category> CATEGORIES = List.of(
            new Category("age", List.of(
                    "\\bhow old are you\\b",
                    "\\byour age\\b",
                    "\\bwhat.{0,10}\\byear were you born\\b",
                    "\\bdate of birth\\b",
                    "\\byour birth ?(date|year)\\b")),

            new Category("gender", List.of(
                    "\\byour gender\\b",
                    "\\bare you (a )?(male|female|man|woman)\\b",
                    "\\bwhat gender\\b",
                    "\\byour (preferred )?pronouns\\b")),

            new Category("religion", List.of(
                    "\\byour religion\\b",
                    "\\bare you (a )?(hindu|muslim|christian|sikh|jain|buddhist|jew(ish)?)\\b",
                    "\\bwhich (religion|faith)\\b",
                    "\\bdo you (pray|fast|observe)\\b",
                    "\\byour religious\\b")),

            new Category("caste", List.of(
                    "\\byour caste\\b",
                    "\\bwhich caste\\b",
                    "\\bcaste (certificate|category)\\b",
                    "\\b(sc|st|obc)\\b.{0,20}\\b(category|certificate|quota)\\b")),

            new Category("marital and family status", List.of(
                    "\\bare you (married|single|divorced|engaged)\\b",
                    "\\byour (marital status|spouse|husband|wife)\\b",
                    "\\bdo you have (children|kids)\\b",
                    "\\bplanning (to|on) (have|hav(e|ing)|start(ing)?) (a )?(child|children|kids|family)\\b",
                    "\\b(have|start) (a )?(child|children|kids|family)\\b.{0,30}\\b(plan|planning|future|soon)\\b",
                    "\\bwhen.{0,20}\\bget married\\b")),

            new Category("pregnancy", List.of(
                    "\\bare you pregnant\\b",
                    "\\bpregnan(cy|t)\\b",
                    "\\bmaternity leave\\b",
                    "\\bplanning.{0,20}\\bmaternity\\b")),

            new Category("disability and health", List.of(
                    "\\byour (disability|medical condition|health condition)\\b",
                    "\\bdo you have (a )?(disability|illness|medical condition)\\b",
                    "\\bmental health (history|condition|diagnosis)\\b")),

            new Category("national origin", List.of(
                    "\\bwhat is your (native|mother) ?tongue\\b",
                    "\\bare you (an? )?(immigrant|foreigner)\\b",
                    "\\byour (nationality|ethnicity|race)\\b"))
    );

    /**
     * Screens a question.
     *
     * @return a verdict naming the prohibited category when the question is refused
     */
    public Verdict screen(String questionText) {
        if (questionText == null || questionText.isBlank()) {
            return Verdict.reject("empty question");
        }

        String normalised = questionText.toLowerCase(Locale.ROOT);

        for (Category category : CATEGORIES) {
            Optional<Pattern> hit = category.firstMatch(normalised);
            if (hit.isPresent()) {
                log.info("Safety filter rejected a question on category '{}'", category.name());
                return Verdict.reject(category.name());
            }
        }
        return Verdict.approve();
    }

    /** Convenience for filtering a generated batch. */
    public boolean isSafe(String questionText) {
        return screen(questionText).approved();
    }

    /**
     * The outcome of screening one question.
     *
     * @param approved         whether the question may be asked
     * @param prohibitedCategory the named category when refused, else null
     */
    public record Verdict(boolean approved, String prohibitedCategory) {

        static Verdict approve() {
            return new Verdict(true, null);
        }

        static Verdict reject(String category) {
            return new Verdict(false, category);
        }

        /**
         * The message shown to the employer. Names the category, per §7.5.8 —
         * a refusal the employer cannot act on is worse than none.
         */
        public String employerMessage() {
            return approved
                    ? null
                    : "This question cannot be used because it asks about " + prohibitedCategory
                      + ". Questions touching age, gender, religion, caste, marital status, "
                      + "pregnancy, disability or national origin are not permitted.";
        }
    }

    private record Category(String name, List<String> patterns) {

        Optional<Pattern> firstMatch(String normalisedText) {
            return patterns.stream()
                    .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE))
                    .filter(p -> p.matcher(normalisedText).find())
                    .findFirst();
        }
    }
}
