package com.interviewengine.scheduling.service;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IcsCalendarWriterTest {

    private final IcsCalendarWriter writer = new IcsCalendarWriter();

    private static final UUID SESSION_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void writesStartAndEndDerivedFromTheDurationTier() {
        String ics = write(OffsetDateTime.of(2026, 9, 1, 10, 30, 0, 0, ZoneOffset.UTC), 35);

        assertThat(ics).contains("DTSTART:20260901T103000Z");
        // Standard tier is 35 minutes.
        assertThat(ics).contains("DTEND:20260901T110500Z");
    }

    @Test
    void normalisesANonUtcStartToUtc() {
        // 16:00 IST is 10:30 UTC.
        OffsetDateTime ist = OffsetDateTime.of(2026, 9, 1, 16, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

        assertThat(write(ist, 35)).contains("DTSTART:20260901T103000Z");
    }

    @Test
    void usesTheSessionIdAsTheEventUid() {
        // A reschedule must update the candidate's existing calendar entry
        // rather than adding a second one, which is what a stable UID buys.
        assertThat(write(OffsetDateTime.now(ZoneOffset.UTC), 35))
                .contains("UID:" + SESSION_ID + "@interviewengine.ai");
    }

    @Test
    void carriesTheSequenceSoClientsAcceptAnUpdate() {
        String ics = writer.write(SESSION_ID, 3, OffsetDateTime.now(ZoneOffset.UTC), 35,
                "Backend Engineer", "Acme", "https://app.interviewengine.ai/room/x");

        // Calendar clients ignore an update whose SEQUENCE has not advanced.
        assertThat(ics).contains("SEQUENCE:3");
    }

    @Test
    void statesTheChromiumRequirement() {
        // §17 lists "candidate arrives on Safari or Firefox" as high-probability,
        // mitigated by stating the requirement before they click.
        String ics = write(OffsetDateTime.now(ZoneOffset.UTC), 35);

        // Commas arrive escaped, as RFC 5545 requires inside a property value.
        assertThat(ics.replace("\r\n ", "")).contains("Chrome\\, Edge\\, Brave or Arc");
    }

    @Test
    void escapesCharactersThatRfc5545GivesMeaningTo() {
        String ics = writer.write(SESSION_ID, 0, OffsetDateTime.now(ZoneOffset.UTC), 35,
                "Engineer, Backend; Senior", "Acme", "https://example.com");

        String unfolded = ics.replace("\r\n ", "");
        assertThat(unfolded).contains("Engineer\\, Backend\\; Senior");
    }

    @Test
    void foldsLinesToTheSeventyFiveOctetLimit() {
        String ics = writer.write(SESSION_ID, 0, OffsetDateTime.now(ZoneOffset.UTC), 60,
                "Staff Distributed Systems Engineer, Platform Infrastructure Group",
                "A Company With A Considerably Long Registered Name Limited",
                "https://app.interviewengine.ai/room/an-unusually-long-session-identifier-value");

        // Clients reject over-long content lines outright.
        for (String line : ics.split("\r\n")) {
            assertThat(line.length()).isLessThanOrEqualTo(75);
        }
    }

    @Test
    void producesAWellFormedSingleEventCalendar() {
        String ics = write(OffsetDateTime.now(ZoneOffset.UTC), 20);

        assertThat(ics).startsWith("BEGIN:VCALENDAR").endsWith("END:VCALENDAR\r\n");
        assertThat(ics).containsOnlyOnce("BEGIN:VEVENT").containsOnlyOnce("END:VEVENT");
        assertThat(ics).contains("VERSION:2.0").contains("STATUS:CONFIRMED");
    }

    @Test
    void includesAFifteenMinuteReminder() {
        assertThat(write(OffsetDateTime.now(ZoneOffset.UTC), 35))
                .contains("BEGIN:VALARM")
                .contains("TRIGGER:-PT15M");
    }

    private String write(OffsetDateTime startAt, int minutes) {
        return writer.write(SESSION_ID, 0, startAt, minutes,
                "Backend Engineer", "Acme", "https://app.interviewengine.ai/room/abc");
    }
}
