package com.interviewiq.ai.service;

/**
 * @deprecated Recall.ai was removed in V038. The in-browser WebRTC interview room
 *             (PRD v3) replaced Recall.ai entirely. Transcripts are now captured
 *             directly by the browser via Web Speech API and stored in
 *             {@code interview_sessions.questions_json} as per-question answer fields.
 *             This class is retained only because file deletion is not supported in
 *             this environment — it is not instantiated or used anywhere.
 */
@Deprecated(since = "V038", forRemoval = true)
public class RecallTranscriptClient {
    private RecallTranscriptClient() {}
}
