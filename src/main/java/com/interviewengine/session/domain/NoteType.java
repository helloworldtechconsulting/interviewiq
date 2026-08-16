package com.interviewengine.session.domain;

/** DB CHECK values: 'SYSTEM', 'INTERVIEWER', 'AI' */
public enum NoteType {
    /** Automated system event (e.g. "Bot joined at 14:32"). */
    SYSTEM,
    /** Free-text note added manually by an employer / recruiter. */
    INTERVIEWER,
    /** AI-generated observation injected post-evaluation. */
    AI
}
