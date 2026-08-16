package com.interviewengine.scheduling.service;

import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Writes the {@code .ics} attachment that accompanies a booking confirmation
 * (PRD v2.1 §7.4.1, §13.3).
 *
 * <p><strong>This is a calendar file, not a calendar integration.</strong> The
 * PRD is careful about the distinction: Google Calendar and Outlook integrations
 * are explicitly out of scope for Phase 1, and "the confirmation email carries an
 * {@code .ics} attachment, which is not the same thing" (§5.2). An attachment
 * needs no OAuth, no per-provider API and no ongoing sync — the candidate opens
 * it and their own calendar client does the rest.
 *
 * <p>Hand-rolled rather than pulling in a calendar library: RFC 5545 is large,
 * but the subset needed for a single non-recurring VEVENT is a dozen lines, and
 * a dependency for that is not worth the supply-chain surface.
 */
@Component
public class IcsCalendarWriter {

    /** RFC 5545 UTC form: 20260901T103000Z. */
    private static final DateTimeFormatter ICS_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    /** RFC 5545 caps content lines at 75 octets, continued by a leading space. */
    private static final int LINE_OCTET_LIMIT = 75;

    /**
     * Builds a single-event calendar for an interview booking.
     *
     * @param sessionId  becomes the event UID, so a rescheduled booking updates
     *                   the existing entry in the candidate's calendar rather
     *                   than adding a second one
     * @param sequence   increment on each reschedule; calendar clients ignore an
     *                   update whose SEQUENCE has not advanced
     * @param startAt    interview start
     * @param minutes    duration from the job's tier
     * @param jobTitle   shown as the event summary
     * @param companyName the hiring company
     * @param joinUrl    the interview room link
     */
    public String write(UUID sessionId,
                        int sequence,
                        OffsetDateTime startAt,
                        int minutes,
                        String jobTitle,
                        String companyName,
                        String joinUrl) {

        OffsetDateTime startUtc = startAt.withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime endUtc = startUtc.plusMinutes(minutes);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        StringBuilder ics = new StringBuilder();
        ics.append("BEGIN:VCALENDAR\r\n")
           .append("VERSION:2.0\r\n")
           .append("PRODID:-//InterviewEngine//Interview Scheduling//EN\r\n")
           .append("CALSCALE:GREGORIAN\r\n")
           .append("METHOD:REQUEST\r\n")
           .append("BEGIN:VEVENT\r\n")
           .append("UID:").append(sessionId).append("@interviewengine.ai\r\n")
           .append("SEQUENCE:").append(sequence).append("\r\n")
           .append("DTSTAMP:").append(ICS_UTC.format(now)).append("\r\n")
           .append("DTSTART:").append(ICS_UTC.format(startUtc)).append("\r\n")
           .append("DTEND:").append(ICS_UTC.format(endUtc)).append("\r\n")
           .append(fold("SUMMARY:" + escape("Interview: " + jobTitle + " at " + companyName)))
           .append(fold("DESCRIPTION:" + escape(description(joinUrl, minutes))))
           .append(fold("URL:" + escape(joinUrl)))
           .append("STATUS:CONFIRMED\r\n")
           // A reminder 15 minutes out. Reminder emails are sent separately
           // (§13.3); this one fires from the candidate's own calendar.
           .append("BEGIN:VALARM\r\n")
           .append("TRIGGER:-PT15M\r\n")
           .append("ACTION:DISPLAY\r\n")
           .append("DESCRIPTION:Your interview starts in 15 minutes\r\n")
           .append("END:VALARM\r\n")
           .append("END:VEVENT\r\n")
           .append("END:VCALENDAR\r\n");

        return ics.toString();
    }

    private String description(String joinUrl, int minutes) {
        // The Chromium requirement is repeated here because the .ics is what the
        // candidate is most likely to open at interview time, and §17 lists
        // arriving on Safari or Firefox as a high-probability risk to be
        // mitigated by stating the requirement early and often.
        return "Your AI interview is scheduled for " + minutes + " minutes.\n\n"
                + "Join here: " + joinUrl + "\n\n"
                + "Please use a Chromium-based desktop browser — Chrome, Edge, Brave or Arc. "
                + "You will need a working camera and microphone.";
    }

    /** Escapes the characters RFC 5545 gives meaning to. */
    private String escape(String value) {
        return value.replace("\\", "\\\\")
                    .replace(";", "\\;")
                    .replace(",", "\\,")
                    .replace("\n", "\\n");
    }

    /**
     * Folds a content line to the 75-octet limit, continuing with a leading
     * space. Clients reject over-long lines, and a description carrying a URL
     * exceeds the limit easily.
     */
    private String fold(String line) {
        if (line.length() <= LINE_OCTET_LIMIT) {
            return line + "\r\n";
        }
        StringBuilder folded = new StringBuilder(line.substring(0, LINE_OCTET_LIMIT));
        int index = LINE_OCTET_LIMIT;
        while (index < line.length()) {
            int end = Math.min(index + LINE_OCTET_LIMIT - 1, line.length());
            folded.append("\r\n ").append(line, index, end);
            index = end;
        }
        return folded.append("\r\n").toString();
    }
}
