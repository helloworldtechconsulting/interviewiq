package com.interviewiq.candidate.service;

import com.interviewiq.candidate.service.CandidateCsvParser.ParsedCsv;
import com.interviewiq.candidate.service.CandidateCsvParser.ParsedRow;
import com.interviewiq.shared.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CSV parsing for bulk candidate import (PRD v2.1 §7.3.1).
 */
class CandidateCsvParserTest {

    private final CandidateCsvParser parser = new CandidateCsvParser();

    private ParsedCsv parse(String csv, Map<String, Integer> mapping) {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), mapping);
    }

    private static final Map<String, Integer> NAME_EMAIL = Map.of("name", 0, "email", 1);

    // =========================================================================
    // Column mapping is proposed, never assumed
    // =========================================================================

    @Test
    void proposesAMappingFromRecognisedHeaders() {
        var proposal = parser.proposeMapping(List.of("Full Name", "Email Address", "Mobile"));

        assertThat(proposal).containsEntry("name", 0)
                            .containsEntry("email", 1)
                            .containsEntry("phone", 2);
    }

    @Test
    void leavesUnrecognisedHeadersOutOfTheProposal() {
        // "We do not guess silently" (§7.3.1). An unmatched header is absent
        // from the proposal, which is what makes the UI ask rather than assume.
        var proposal = parser.proposeMapping(List.of("Candidate", "Contact", "Notes"));

        assertThat(proposal).containsKey("name");
        assertThat(proposal).doesNotContainKey("email");   // "Contact" is ambiguous
    }

    @Test
    void refusesAMappingWithoutTheRequiredColumns() {
        assertThatThrownBy(() -> parse("a,b\n1,2\n", Map.of("name", 0)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("name and an email");
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    @Test
    void parsesAStraightforwardFile() {
        ParsedCsv result = parse("""
                name,email
                Priya Sharma,priya@example.com
                Arjun Rao,arjun@example.com
                """, NAME_EMAIL);

        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows()).allMatch(ParsedRow::isValid);
        assertThat(result.rows().get(0).name()).isEqualTo("Priya Sharma");
    }

    @Test
    void lowercasesEmailsSoDuplicateDetectionWorks() {
        ParsedCsv result = parse("name,email\nPriya,PRIYA@Example.COM\n", NAME_EMAIL);

        assertThat(result.rows().get(0).email()).isEqualTo("priya@example.com");
    }

    @Test
    void handlesQuotedFieldsContainingCommas() {
        ParsedCsv result = parse("""
                name,email
                "Sharma, Priya",priya@example.com
                """, NAME_EMAIL);

        assertThat(result.rows().get(0).name()).isEqualTo("Sharma, Priya");
    }

    @Test
    void handlesEscapedQuotesInsideAQuotedField() {
        ParsedCsv result = parse("""
                name,email
                "Priya ""Pri"" Sharma",priya@example.com
                """, NAME_EMAIL);

        assertThat(result.rows().get(0).name()).isEqualTo("Priya \"Pri\" Sharma");
    }

    @Test
    void handlesANewlineInsideAQuotedField() {
        // Spreadsheets export these routinely; treating the wrapped line as a
        // new row would silently corrupt the import.
        ParsedCsv result = parse("name,email\n\"Priya\nSharma\",priya@example.com\n", NAME_EMAIL);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).name()).isEqualTo("Priya\nSharma");
    }

    @Test
    void toleratesRaggedRowsWithMissingTrailingColumns() {
        ParsedCsv result = parse("""
                name,email,phone
                Priya,priya@example.com,9876543210
                Arjun,arjun@example.com
                """, Map.of("name", 0, "email", 1, "phone", 2));

        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(1).phone()).isEmpty();
        assertThat(result.rows().get(1).isValid()).isTrue();   // phone is optional
    }

    // =========================================================================
    // Validation — what the preview reports
    // =========================================================================

    @Test
    void flagsAMissingName() {
        ParsedCsv result = parse("name,email\n,priya@example.com\n", NAME_EMAIL);

        assertThat(result.rows().get(0).isValid()).isFalse();
        assertThat(result.rows().get(0).problems()).anyMatch(p -> p.contains("name"));
    }

    @Test
    void flagsAnInvalidEmail() {
        ParsedCsv result = parse("name,email\nPriya,not-an-email\n", NAME_EMAIL);

        assertThat(result.rows().get(0).isValid()).isFalse();
        assertThat(result.rows().get(0).problems()).anyMatch(p -> p.contains("valid address"));
    }

    @Test
    void reportsTheOriginalLineNumberSoTheRecruiterCanFindTheRow() {
        ParsedCsv result = parse("""
                name,email
                Priya,priya@example.com
                ,broken
                """, NAME_EMAIL);

        // Line 3 of the file: header is 1, first candidate is 2.
        assertThat(result.rows().get(1).lineNumber()).isEqualTo(3);
    }

    @Test
    void returnsInvalidRowsRatherThanDroppingThem() {
        ParsedCsv result = parse("""
                name,email
                Priya,priya@example.com
                ,broken
                Arjun,arjun@example.com
                """, NAME_EMAIL);

        // The preview must show what is wrong, so invalid rows survive parsing.
        assertThat(result.rows()).hasSize(3);
        assertThat(result.rows().stream().filter(ParsedRow::isValid)).hasSize(2);
    }

    // =========================================================================
    // Limits
    // =========================================================================

    @Test
    void refusesAFileBeyondTheTwoHundredRowCap() {
        StringBuilder csv = new StringBuilder("name,email\n");
        for (int i = 0; i < 201; i++) {
            csv.append("Candidate ").append(i).append(",c").append(i).append("@example.com\n");
        }

        assertThatThrownBy(() -> parse(csv.toString(), NAME_EMAIL))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("200");
    }

    @Test
    void acceptsExactlyTwoHundredRows() {
        StringBuilder csv = new StringBuilder("name,email\n");
        for (int i = 0; i < 200; i++) {
            csv.append("Candidate ").append(i).append(",c").append(i).append("@example.com\n");
        }

        assertThat(parse(csv.toString(), NAME_EMAIL).rows()).hasSize(200);
    }

    @Test
    void refusesAnEmptyFile() {
        assertThatThrownBy(() -> parse("", NAME_EMAIL))
                .isInstanceOf(ValidationException.class);
    }
}
