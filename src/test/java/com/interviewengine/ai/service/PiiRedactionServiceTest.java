package com.interviewengine.ai.service;

import com.interviewengine.candidate.domain.Candidate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PII redaction before every outbound LLM call (PRD v2.1 §7.5.6).
 *
 * <p>"Mandatory, on every outbound LLM call, for every workflow." The corrected
 * data-residency position in §8 depends on this holding: candidate transcripts
 * are processed outside India by an external model, and redaction is what keeps
 * that defensible.
 */
class PiiRedactionServiceTest {

    private final PiiRedactionService service = new PiiRedactionService();

    private Candidate candidate(String name, String email, String phone) {
        Candidate c = new Candidate();
        c.setFullName(name);
        c.setEmail(email);
        c.setPhone(phone);
        return c;
    }

    // =========================================================================
    // Known identifiers
    // =========================================================================

    @Test
    void redactsTheCandidatesName() {
        Candidate c = candidate("Priya Sharma", "priya@example.com", null);

        String redacted = service.redact("Priya Sharma has five years of Java experience.", c);

        assertThat(redacted).doesNotContain("Priya").doesNotContain("Sharma");
        assertThat(redacted).contains("[CANDIDATE]");
    }

    @Test
    void redactsEachNamePartSeparately() {
        // A resume says the full name in the header and a first name in prose.
        Candidate c = candidate("Priya Sharma", "priya@example.com", null);

        String redacted = service.redact("Priya led the migration. Sharma also mentored two juniors.", c);

        assertThat(redacted).doesNotContain("Priya").doesNotContain("Sharma");
    }

    @Test
    void redactsTheCandidatesEmailAndPhone() {
        Candidate c = candidate("Priya Sharma", "priya.sharma@example.com", "+91 98765 43210");

        String redacted = service.redact(
                "Contact: priya.sharma@example.com or +91 98765 43210", c);

        assertThat(redacted).contains("[EMAIL_REDACTED]").contains("[PHONE_REDACTED]");
        assertThat(redacted).doesNotContain("priya.sharma@example.com");
        assertThat(redacted).doesNotContain("98765");
    }

    @Test
    void isCaseInsensitiveOnNames() {
        Candidate c = candidate("Priya Sharma", "p@example.com", null);

        assertThat(service.redact("PRIYA is an excellent engineer.", c))
                .doesNotContainIgnoringCase("priya");
    }

    // =========================================================================
    // Generic patterns — for identifiers we were never told about
    // =========================================================================

    @Test
    void redactsAnEmailSpokenDuringTheInterview() {
        // Exact-match alone would miss this: it is not the address on file.
        String redacted = service.redact(
                "You can reach my referee at manager@previouscompany.co.in", null);

        assertThat(redacted).contains("[EMAIL_REDACTED]").doesNotContain("previouscompany");
    }

    @Test
    void redactsAPhoneNumberReadAloud() {
        String redacted = service.redact("My number is 9876543210, please call anytime.", null);

        assertThat(redacted).doesNotContain("9876543210");
    }

    // =========================================================================
    // Not over-redacting
    // =========================================================================

    @Test
    void leavesTechnicalContentIntact() {
        Candidate c = candidate("Priya Sharma", "priya@example.com", null);
        String answer = "I used Spring Boot 3.3 with PostgreSQL 16 and cut p95 latency to 180ms.";

        // The model still has to be able to score the answer.
        assertThat(service.redact(answer, c)).isEqualTo(answer);
    }

    @Test
    void skipsVeryShortNamePartsThatWouldCorruptOrdinaryText() {
        // An initial matched as a whole word would turn every standalone "S" in
        // a transcript into a placeholder, destroying the text being scored.
        Candidate c = candidate("S K Rao", "sk@example.com", null);

        String redacted = service.redact("S3 buckets are versioned. K is the partition key.", c);

        assertThat(redacted).contains("S3").contains("K is the partition key");
    }

    @Test
    void handlesNullAndBlankInput() {
        assertThat(service.redact(null, null)).isNull();
        assertThat(service.redact("", null)).isEmpty();
    }

    // =========================================================================
    // The post-redaction check
    // =========================================================================

    @Test
    void verificationPassesOnCleanText() {
        assertThat(service.verifyRedacted(
                "I built a rate limiter using Redis and a token bucket.", "test")).isTrue();
    }

    @Test
    void verificationFlagsSurvivingIdentifiers() {
        assertThat(service.verifyRedacted("reach me at a@b.com", "test")).isFalse();
    }

    @Test
    void redactedOutputPassesItsOwnVerification() {
        Candidate c = candidate("Priya Sharma", "priya@example.com", "+91 98765 43210");
        String raw = "Priya Sharma, priya@example.com, +91 98765 43210, five years of Java.";

        assertThat(service.verifyRedacted(service.redact(raw, c), "test")).isTrue();
    }
}
