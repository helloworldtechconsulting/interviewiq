package com.interviewiq.session.domain;

/** DB CHECK values for session_events.event_type (V035) */
public enum SessionEventType {
    SESSION_STARTED,
    SESSION_ENDED,
    TAB_SWITCH,
    CAMERA_OFF,
    CAMERA_ON,
    MULTI_FACE_DETECTED,
    AUDIO_MUTED,
    AUDIO_UNMUTED,
    SCREEN_SHARE_STARTED,
    SCREEN_SHARE_STOPPED,
    CONNECTION_LOST,
    CONNECTION_RESTORED
}
