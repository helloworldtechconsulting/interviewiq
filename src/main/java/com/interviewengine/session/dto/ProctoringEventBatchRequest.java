package com.interviewengine.session.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A batch of proctoring events replayed over REST after a WebSocket drop
 * (PRD v2.1 §11, {@code POST /candidate/sessions/{id}/events}).
 *
 * <p>No session identifier: it comes from the invite token. A client-supplied
 * session ID would let any valid candidate token attach proctoring events to
 * another candidate's interview.
 *
 * @param events buffered signals, in any order — they carry their own timestamps
 */
public record ProctoringEventBatchRequest(

        @NotNull
        @NotEmpty(message = "At least one event is required.")
        /*
         * Capped so a bug in the client's buffering — or a deliberately hostile
         * body — cannot turn one request into an unbounded write loop. An
         * hour-long interview on a badly flapping connection produces events in
         * the tens, so 200 is far above any legitimate backlog while still
         * being a bound.
         */
        @Size(max = 200, message = "A batch may contain at most 200 events.")
        @Valid
        List<Event> events
) {

    /**
     * @param type       {@code tab_switch} or {@code camera_off}; anything else
     *                   is dropped server-side rather than rejected, so that a
     *                   client sending a type we do not know about does not
     *                   lose the rest of its batch
     * @param occurredAt ISO-8601 instant from the browser at the time the event
     *                   happened, not at replay time — the ordering is what
     *                   makes these signals readable, and stamping them on
     *                   arrival would collapse an hour of events into one moment
     */
    public record Event(
            @NotNull(message = "Event type is required.")
            @Size(max = 100)
            String type,

            @Size(max = 40)
            String occurredAt
    ) {}
}
