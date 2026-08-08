package com.interviewiq.shared.config;

/**
 * @deprecated Recall.ai was removed in V038 (PRD v3 migration to in-browser WebRTC).
 *             This class is retained only because file deletion is not supported in
 *             this environment — it is not registered as a Spring bean or used anywhere.
 */
@Deprecated(since = "V038", forRemoval = true)
public class RecallProperties {
    private RecallProperties() {}
}
