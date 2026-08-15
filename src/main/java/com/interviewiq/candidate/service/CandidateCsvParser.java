package com.interviewiq.candidate.service;

import com.interviewiq.candidate.domain.CandidateImportBatch;
import com.interviewiq.shared.exception.ValidationException;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses an uploaded candidate CSV (PRD v2.1 §7.3.1).
 *
 * <p>Required columns are name and email; phone and résumé URL are optional.
 *
 * <h2>Column mapping is proposed, never assumed</h2>
 *
 * <p>§7.3.1: "Column mapping is explicit and user-driven — the UI proposes a
 * mapping from the header row and the user confirms or corrects it. <strong>We do
 * not guess silently.</strong>" So {@link #proposeMapping} returns a suggestion
 * for the UI to show, and {@link #parse} takes the mapping the recruiter actually
 * confirmed. A file whose header says "Contact" could mean email or phone, and
 * quietly picking one produces fifty candidates with unusable records.
 */
@Component
public class CandidateCsvParser {

    /**
     * Deliberately permissive. Strict RFC 5322 rejects addresses that work in
     * practice, and the cost of accepting one bad address is a bounced invite,
     * while the cost of rejecting a good one is a candidate silently missing
     * from a hiring drive.
     */
    private static final Pattern EMAIL =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$");

    /** Header aliases the UI's proposed mapping recognises. */
    private static final Map<String, List<String>> FIELD_ALIASES = Map.of(
            "name", List.of("name", "full name", "fullname", "candidate", "candidate name"),
            "email", List.of("email", "email address", "e-mail", "mail"),
            "phone", List.of("phone", "mobile", "phone number", "contact number", "mobile number"),
            "resumeUrl", List.of("resume", "resume url", "cv", "cv url", "resume link"));

    /**
     * Reads the header row and proposes a field mapping for the recruiter to
     * confirm or correct.
     *
     * @return proposed field name → CSV column index; fields with no confident
     *         match are simply absent, which is what prompts the UI to ask
     */
    public Map<String, Integer> proposeMapping(List<String> headerRow) {
        Map<String, Integer> proposal = new LinkedHashMap<>();

        for (int i = 0; i < headerRow.size(); i++) {
            String header = headerRow.get(i).strip().toLowerCase(Locale.ROOT);
            FIELD_ALIASES.forEach((field, aliases) -> {
                if (!proposal.containsKey(field) && aliases.contains(header)) {
                    proposal.put(field, headerRow.indexOf(headerRow.get(proposal.size())));
                }
            });
        }

        // Rebuilt explicitly rather than inside the lambda above, to keep the
        // index unambiguous when two headers share an alias.
        proposal.clear();
        for (int i = 0; i < headerRow.size(); i++) {
            String header = headerRow.get(i).strip().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, List<String>> entry : FIELD_ALIASES.entrySet()) {
                if (!proposal.containsKey(entry.getKey()) && entry.getValue().contains(header)) {
                    proposal.put(entry.getKey(), i);
                }
            }
        }
        return proposal;
    }

    /**
     * Parses the file using a confirmed mapping.
     *
     * @param mapping field name → column index, as confirmed by the recruiter
     * @return every row, valid and invalid alike — the preview has to show both
     * @throws ValidationException if the file is empty, unreadable, or exceeds the cap
     */
    public ParsedCsv parse(InputStream csv, Map<String, Integer> mapping) {
        if (!mapping.containsKey("name") || !mapping.containsKey("email")) {
            throw new ValidationException("The mapping must include both a name and an email column.");
        }

        List<String[]> rows = readRows(csv);
        if (rows.isEmpty()) {
            throw new ValidationException("The file contains no rows.");
        }

        // Skip the header.
        List<String[]> dataRows = rows.subList(1, rows.size());
        if (dataRows.size() > CandidateImportBatch.MAX_ROWS) {
            throw new ValidationException(
                    "A single import may contain at most " + CandidateImportBatch.MAX_ROWS
                            + " candidates. This file has " + dataRows.size() + ".");
        }

        List<ParsedRow> parsed = new ArrayList<>(dataRows.size());
        for (int i = 0; i < dataRows.size(); i++) {
            parsed.add(toRow(dataRows.get(i), mapping, i + 2)); // +2: 1-indexed, past the header
        }
        return new ParsedCsv(headerOf(rows), parsed);
    }

    /** Reads the header row alone, for the mapping proposal. */
    public List<String> readHeader(InputStream csv) {
        List<String[]> rows = readRows(csv);
        if (rows.isEmpty()) {
            throw new ValidationException("The file contains no rows.");
        }
        return List.of(rows.get(0));
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    private ParsedRow toRow(String[] columns, Map<String, Integer> mapping, int lineNumber) {
        String name  = valueAt(columns, mapping.get("name"));
        String email = valueAt(columns, mapping.get("email"));
        String phone = valueAt(columns, mapping.get("phone"));
        String resumeUrl = valueAt(columns, mapping.get("resumeUrl"));

        List<String> problems = new ArrayList<>();
        if (name.isBlank()) {
            problems.add("name is missing");
        }
        if (email.isBlank()) {
            problems.add("email is missing");
        } else if (!EMAIL.matcher(email).matches()) {
            problems.add("email is not a valid address");
        }

        return new ParsedRow(lineNumber, name, email.toLowerCase(Locale.ROOT), phone, resumeUrl, problems);
    }

    private String valueAt(String[] columns, Integer index) {
        if (index == null || index < 0 || index >= columns.length) {
            return "";
        }
        return columns[index].strip();
    }

    private List<String> headerOf(List<String[]> rows) {
        return List.of(rows.get(0));
    }

    /**
     * Minimal RFC 4180 reader: comma-separated, double quotes for escaping, and
     * a doubled quote inside a quoted field.
     *
     * <p>Hand-rolled rather than pulling in a CSV library. The format accepted
     * here is exactly the one the UI documents, and a dependency for ~30 lines
     * of well-understood parsing is not worth the supply-chain surface.
     */
    private List<String[]> readRows(InputStream input) {
        List<String[]> rows = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {

            String line;
            StringBuilder pending = new StringBuilder();
            boolean insideQuotes = false;

            while ((line = reader.readLine()) != null) {
                // A quoted field may contain a newline, so a CSV "line" is not
                // always a file line.
                if (insideQuotes) {
                    pending.append('\n').append(line);
                } else {
                    pending.setLength(0);
                    pending.append(line);
                }

                insideQuotes = countUnescapedQuotes(pending.toString()) % 2 != 0;
                if (insideQuotes) {
                    continue;
                }

                String row = pending.toString();
                if (!row.isBlank()) {
                    rows.add(splitRow(row));
                }
            }
        } catch (IOException e) {
            throw new ValidationException("The file could not be read. Please check it is a valid CSV.");
        }
        return rows;
    }

    private long countUnescapedQuotes(String text) {
        long count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '"') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    i++;          // doubled quote — an escaped literal, not a delimiter
                } else {
                    count++;
                }
            }
        }
        return count;
    }

    private String[] splitRow(String row) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < row.length(); i++) {
            char c = row.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < row.length() && row.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields.toArray(String[]::new);
    }

    // =========================================================================
    // Result types
    // =========================================================================

    public record ParsedCsv(List<String> header, List<ParsedRow> rows) {}

    /**
     * One CSV row.
     *
     * @param lineNumber the line in the original file, so the preview can point
     *                   the recruiter at the row to fix
     * @param problems   empty when the row is importable
     */
    public record ParsedRow(int lineNumber,
                            String name,
                            String email,
                            String phone,
                            String resumeUrl,
                            List<String> problems) {

        public boolean isValid() {
            return problems.isEmpty();
        }
    }
}
