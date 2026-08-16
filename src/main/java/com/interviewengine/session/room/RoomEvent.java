package com.interviewengine.session.room;

/**
 * The WebSocket event contract for the interview room (PRD v2.1 §7.5.2).
 *
 * <p><strong>One connection, eight events.</strong> The contract is deliberately
 * this small, and the reason is the single most important architectural fact
 * about this product: all media stays in the candidate's browser.
 * {@code getUserMedia}, {@code MediaRecorder}, {@code SpeechSynthesis} and
 * {@code SpeechRecognition} all run client-side, and the 480p recording uploads
 * browser-to-storage by pre-signed URL without ever passing through this server.
 *
 * <p>So this socket carries text and control events only — roughly 50–100 KB of
 * state per live interview, and about one message every 60–90 seconds. That is
 * what allows a two-pod deployment to carry 25 or more simultaneous interviews,
 * and what makes ₹100 per interview a profitable price.
 *
 * <table>
 *   <caption>Event directions</caption>
 *   <tr><td>C → S</td><td>{@link #SESSION_START}, {@link #ANSWER_SUBMIT},
 *                          {@link #PROCTORING_EVENT}, {@link #SESSION_END}</td></tr>
 *   <tr><td>S → C</td><td>{@link #QUESTION_NEXT}, {@link #FOLLOWUP_QUESTION},
 *                          {@link #TIMER_WARNING}, {@link #SESSION_TERMINATED}</td></tr>
 * </table>
 */
public enum RoomEvent {

    // ── Client → server ─────────────────────────────────────────────────────

    /** Transitions the session to {@code IN_PROGRESS} and starts the hard timer. */
    SESSION_START("session.start"),

    /** {@code questionIndex}, {@code transcriptText}, {@code durationSeconds}. */
    ANSWER_SUBMIT("answer.submit"),

    /** {@code type} ({@code tab_switch} | {@code camera_off}), {@code timestamp}. */
    PROCTORING_EVENT("proctoring.event"),

    /** Transitions the session to {@code EVALUATING}. */
    SESSION_END("session.end"),

    /**
     * Reconnect-and-resume. Not one of the eight, but required by the same
     * section: "on socket loss the browser reconnects with the same session JWT
     * and the backend replays current question state". §7.5.2 calls a deploy or
     * scale-in killing a live interview "the single worst bug this product could
     * ship", and resume is half of the mitigation.
     */
    SESSION_RESUME("session.resume"),

    // ── Server → client ─────────────────────────────────────────────────────

    /** {@code index}, {@code text}, {@code type}, {@code isFollowUp}. */
    QUESTION_NEXT("question.next"),

    /** {@code text}. */
    FOLLOWUP_QUESTION("followup.question"),

    /** {@code minutesRemaining} — pushed at 10, 5 and 1 minute before the cutoff. */
    TIMER_WARNING("timer.warning"),

    /** {@code reason} — the hard timer, or a critical proctoring violation. */
    SESSION_TERMINATED("session.terminated"),

    /** Acknowledges a client event, so the browser knows a submit was persisted. */
    ACK("ack"),

    /** Carries a recoverable problem without tearing the socket down. */
    ERROR("error");

    private final String wireName;

    RoomEvent(String wireName) {
        this.wireName = wireName;
    }

    /** The {@code type} field on the wire, e.g. {@code question.next}. */
    public String wireName() {
        return wireName;
    }

    /** Resolves an inbound {@code type}, or null if unrecognised. */
    public static RoomEvent fromWireName(String wireName) {
        for (RoomEvent event : values()) {
            if (event.wireName.equals(wireName)) {
                return event;
            }
        }
        return null;
    }

    /** Whether the client is permitted to send this event. */
    public boolean isClientOriginated() {
        return this == SESSION_START
                || this == ANSWER_SUBMIT
                || this == PROCTORING_EVENT
                || this == SESSION_END
                || this == SESSION_RESUME;
    }
}
