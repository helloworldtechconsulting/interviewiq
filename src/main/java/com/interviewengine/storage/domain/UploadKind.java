package com.interviewengine.storage.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The classes of object a client may upload by pre-signed URL, each with its own
 * MIME allow-list, size ceiling and key namespace.
 *
 * <p>PRD v2.1 §7.1.3 requires that every pre-signed-URL confirmation verify object
 * ownership, and lists "no file size, MIME or ownership validation on uploads" as a
 * ship-blocking defect. This enum is the single place those limits are declared, so
 * a new upload path cannot be added without stating its bounds.
 *
 * <p>Keys are always {@code <prefix>/<companyId>/<entityId>/<filename>.<ext>} —
 * derived on the server from the authenticated tenant and the target entity, never
 * accepted from the client. The company segment is what makes a cross-tenant key
 * detectable: a key that does not start with the caller's own company id is
 * rejected before it is ever stored.
 */
public enum UploadKind {

    /** Candidate résumé — PDF or DOCX, 5 MB (PRD §7.3). */
    RESUME("resumes", 5L * 1024 * 1024, Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")),

    /** Job description — PDF or DOCX, 10 MB (PRD §7.2). */
    JOB_DESCRIPTION("jd", 10L * 1024 * 1024, Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")),

    /** Company logo — raster image, 2 MB. */
    COMPANY_LOGO("logos", 2L * 1024 * 1024, Set.of(
            "image/png", "image/jpeg", "image/webp")),

    /**
     * Interview recording — 480p WebM uploaded browser-to-storage, 500 MB
     * (PRD §7.5.3). A 60-minute Comprehensive interview is roughly 270 MB, so the
     * ceiling carries the longest tier with headroom.
     */
    RECORDING("recordings", 500L * 1024 * 1024, Set.of("video/webm"));

    private static final Map<String, String> EXTENSIONS = Map.of(
            "application/pdf", "pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx",
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp",
            "video/webm", "webm");

    private final String keyPrefix;
    private final long maxBytes;
    private final Set<String> allowedContentTypes;

    UploadKind(String keyPrefix, long maxBytes, Set<String> allowedContentTypes) {
        this.keyPrefix = keyPrefix;
        this.maxBytes = maxBytes;
        this.allowedContentTypes = allowedContentTypes;
    }

    public String getKeyPrefix() { return keyPrefix; }

    public long getMaxBytes() { return maxBytes; }

    public Set<String> getAllowedContentTypes() { return allowedContentTypes; }

    /**
     * Normalises a client-supplied {@code Content-Type}, dropping any parameters
     * such as {@code ; charset=utf-8} so that {@code application/pdf; charset=x}
     * matches the allow-list rather than being rejected on a technicality.
     */
    public static String normaliseContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String bare = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
        return bare.trim().toLowerCase(Locale.ROOT);
    }

    public boolean permits(String contentType) {
        return allowedContentTypes.contains(normaliseContentType(contentType));
    }

    /** File extension for an allowed content type, e.g. {@code pdf}. */
    public String extensionFor(String contentType) {
        return EXTENSIONS.getOrDefault(normaliseContentType(contentType), "bin");
    }

    /** Human-readable size ceiling for error messages, e.g. {@code 5 MB}. */
    public String maxSizeLabel() {
        return (maxBytes / (1024 * 1024)) + " MB";
    }
}
