package com.interviewengine.session.domain;

/**
 * Proctoring signals captured by the interview room (PRD v2.1 §7.5.4).
 *
 * <p>DB CHECK values: {@code 'TAB_SWITCH'}, {@code 'CAMERA_OFF'} (see V047).
 *
 * <h2>Why only two</h2>
 *
 * <p>Both are a few lines of browser code and genuinely free. Multi-face
 * detection is deferred to Phase 2 for a concrete reason rather than a scheduling
 * one: the browser {@code FaceDetector} API sits behind Chrome's
 * <em>Experimental Web Platform Features</em> flag and is not enabled by default,
 * and no candidate will turn on a Chrome flag to take an interview. Real
 * multi-face detection means bundling a MediaPipe or face-api.js model — a 2–6 MB
 * WASM and model download running on-device. That is a real feature with a real
 * first-load cost, not a free one.
 *
 * <h2>These never auto-fail anyone</h2>
 *
 * <p>Events are informational signals for the recruiter, shown on the report as a
 * chronological list. The platform performs no automated rejection and takes no
 * ranking action of its own (§7.10). A sustained critical violation may terminate
 * a session, but even then the partial transcript is still evaluated and clearly
 * flagged as incomplete.
 */
public enum ProctoringEventType {

    /** Fired from the Page Visibility API when the interview room loses visibility. */
    TAB_SWITCH,

    /** Fired when the video track ends or is muted. */
    CAMERA_OFF
}
