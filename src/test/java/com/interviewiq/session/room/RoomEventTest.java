package com.interviewiq.session.room;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The eight-event WebSocket contract (PRD v2.1 §7.5.2).
 *
 * <p>The wire names are a published contract the browser depends on, so they are
 * pinned here rather than left to whatever the enum happens to serialise to.
 */
class RoomEventTest {

    @Test
    void wireNamesMatchThePublishedContract() {
        // Client → server
        assertThat(RoomEvent.SESSION_START.wireName()).isEqualTo("session.start");
        assertThat(RoomEvent.ANSWER_SUBMIT.wireName()).isEqualTo("answer.submit");
        assertThat(RoomEvent.PROCTORING_EVENT.wireName()).isEqualTo("proctoring.event");
        assertThat(RoomEvent.SESSION_END.wireName()).isEqualTo("session.end");

        // Server → client
        assertThat(RoomEvent.QUESTION_NEXT.wireName()).isEqualTo("question.next");
        assertThat(RoomEvent.FOLLOWUP_QUESTION.wireName()).isEqualTo("followup.question");
        assertThat(RoomEvent.TIMER_WARNING.wireName()).isEqualTo("timer.warning");
        assertThat(RoomEvent.SESSION_TERMINATED.wireName()).isEqualTo("session.terminated");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "session.start", "answer.submit", "proctoring.event", "session.end",
            "question.next", "followup.question", "timer.warning", "session.terminated",
    })
    void everyContractEventResolvesFromItsWireName(String wireName) {
        assertThat(RoomEvent.fromWireName(wireName)).isNotNull();
    }

    @Test
    void anUnknownWireNameResolvesToNull() {
        assertThat(RoomEvent.fromWireName("session.hijack")).isNull();
        assertThat(RoomEvent.fromWireName("")).isNull();
    }

    // =========================================================================
    // Direction enforcement
    // =========================================================================

    @Test
    void onlyClientEventsMayArriveFromTheClient() {
        assertThat(RoomEvent.SESSION_START.isClientOriginated()).isTrue();
        assertThat(RoomEvent.ANSWER_SUBMIT.isClientOriginated()).isTrue();
        assertThat(RoomEvent.PROCTORING_EVENT.isClientOriginated()).isTrue();
        assertThat(RoomEvent.SESSION_END.isClientOriginated()).isTrue();
        assertThat(RoomEvent.SESSION_RESUME.isClientOriginated()).isTrue();
    }

    @Test
    void serverEventsAreRefusedIfSentByTheClient() {
        // Without this a candidate could push their own question.next and skip
        // ahead through the bank, or fake a session.terminated.
        assertThat(RoomEvent.QUESTION_NEXT.isClientOriginated()).isFalse();
        assertThat(RoomEvent.FOLLOWUP_QUESTION.isClientOriginated()).isFalse();
        assertThat(RoomEvent.TIMER_WARNING.isClientOriginated()).isFalse();
        assertThat(RoomEvent.SESSION_TERMINATED.isClientOriginated()).isFalse();
    }

    @Test
    void everyEventHasADistinctWireName() {
        long distinct = java.util.Arrays.stream(RoomEvent.values())
                .map(RoomEvent::wireName)
                .distinct()
                .count();

        assertThat(distinct).isEqualTo(RoomEvent.values().length);
    }
}
