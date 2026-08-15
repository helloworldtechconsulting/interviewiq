package com.interviewiq.ai.service;

import com.interviewiq.candidate.domain.Candidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips candidate PII from every outbound LLM payload (PRD v2.1 §7.5.6).
 *
 * <p><strong>Mandatory, on every outbound LLM call, for every workflow.</strong>
 * Candidate name, email address and phone number are removed and an opaque
 * {@code candidate_ref} is passed instead; identity is re-attached locally when
 * the report is persisted.
 *
 * <p>The justification in the PRD is worth keeping in view, because it explains
 * why this is cheap rather than a compromise: "The evaluation model does not need
 * to know who the candidate is in order to score an answer about Spring Boot.
 * This costs nothing, removes most of the residency exposure, and is the correct
 * answer when a customer's security team asks."
 *
 * <p>That last clause is doing real work. §8 records that the v1.0 claim of
 * India-only storage <em>and</em> processing was false and remains withdrawn —
 * no external LLM keeps inference in India. Redaction is what makes the corrected,
 * narrower claim defensible.
 *
 * <h2>Scope</h2>
 *
 * <p>Redaction is belt-and-braces: known identifiers are replaced by exact match,
 * and generic email and phone patterns catch identifiers that appear in free text
 * the candidate spoke or that were parsed out of a résumé. Neither alone is
 * enough — exact match misses a phone number the candidate reads aloud, and
 * patterns alone miss a name.
 */
@Service
public class PiiRedactionService {

    private static final Logger log = LoggerFactory.getLogger(PiiRedactionService.class);

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * Indian mobile numbers with or without a country code, plus generic
     * long digit runs that read as phone numbers. Deliberately broad: a false
     * positive costs an unnecessary placeholder in a transcript, while a false
     * negative sends a candidate's number to a third-party model.
     */
    private static final Pattern PHONE = Pattern.compile(
            "(?:(?:\\+|00)91[\\s-]?)?(?:\\(?\\d{3,5}\\)?[\\s-]?)?\\d{5}[\\s-]?\\d{5}"
                    + "|\\+?\\d[\\d\\s().-]{8,}\\d");

    static final String EMAIL_PLACEHOLDER = "[EMAIL_REDACTED]";
    static final String PHONE_PLACEHOLDER = "[PHONE_REDACTED]";
    static final String NAME_PLACEHOLDER  = "[CANDIDATE]";

    /**
     * Redacts a payload using what we know about this candidate plus the generic
     * patterns.
     *
     * @param text      the text about to be sent to a model
     * @param candidate the candidate it concerns; may be null for JD-only calls
     * @return the redacted text, safe to send
     */
    public String redact(String text, Candidate candidate) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String redacted = text;

        if (candidate != null) {
            redacted = redactKnownIdentifiers(redacted, candidate);
        }

        redacted = EMAIL.matcher(redacted).replaceAll(EMAIL_PLACEHOLDER);
        redacted = PHONE.matcher(redacted).replaceAll(PHONE_PLACEHOLDER);

        return redacted;
    }

    /** Redaction with no candidate context — for JD text and other non-personal payloads. */
    public String redact(String text) {
        return redact(text, null);
    }

    /**
     * Replaces the candidate's own name, email and phone wherever they appear.
     *
     * <p>Names are matched whole-word and case-insensitively, and each part of a
     * full name is matched separately: a résumé says "Priya Sharma" in the header
     * and "Priya" in a summary line, and a transcript may contain either.
     *
     * <p>Very short name parts are skipped. A two-letter name fragment matched as
     * a whole word would redact ordinary English — an initial "S" would turn every
     * standalone "S" in a transcript into a placeholder, which corrupts the text
     * the model has to score without protecting anything.
     */
    private String redactKnownIdentifiers(String text, Candidate candidate) {
        String result = text;

        if (candidate.getEmail() != null && !candidate.getEmail().isBlank()) {
            result = replaceLiteralIgnoreCase(result, candidate.getEmail(), EMAIL_PLACEHOLDER);
        }
        if (candidate.getPhone() != null && !candidate.getPhone().isBlank()) {
            result = replaceLiteralIgnoreCase(result, candidate.getPhone(), PHONE_PLACEHOLDER);
        }
        if (candidate.getFullName() != null && !candidate.getFullName().isBlank()) {
            for (String part : nameParts(candidate.getFullName())) {
                result = result.replaceAll(
                        "(?i)\\b" + Pattern.quote(part) + "\\b",
                        Matcher.quoteReplacement(NAME_PLACEHOLDER));
            }
        }
        return result;
    }

    private List<String> nameParts(String fullName) {
        List<String> parts = new ArrayList<>();
        // Longest first, so "Priya Sharma" is replaced before "Priya" would
        // leave a dangling surname behind.
        parts.add(fullName.strip());
        for (String part : fullName.strip().split("\\s+")) {
            if (part.length() > 2) {
                parts.add(part);
            }
        }
        return parts;
    }

    private String replaceLiteralIgnoreCase(String text, String literal, String placeholder) {
        return Pattern.compile(Pattern.quote(literal.strip()), Pattern.CASE_INSENSITIVE)
                .matcher(text)
                .replaceAll(Matcher.quoteReplacement(placeholder));
    }

    /**
     * Asserts that a payload carries no obvious PII, for use immediately before
     * an outbound call.
     *
     * <p>Logs rather than throws. Blocking an interview because a regex found a
     * digit sequence that looks like a phone number would be a worse outcome than
     * the leak it guards against, and the redaction above has already run — this
     * is a monitoring signal that redaction missed something, not a gate.
     *
     * @return true if the payload looks clean
     */
    public boolean verifyRedacted(String text, String context) {
        if (text == null) {
            return true;
        }
        boolean clean = true;
        if (EMAIL.matcher(text).find()) {
            log.warn("PII check: an email-like string survived redaction in {}", context);
            clean = false;
        }
        if (PHONE.matcher(text).find()) {
            log.warn("PII check: a phone-like string survived redaction in {}", context);
            clean = false;
        }
        return clean;
    }
}
