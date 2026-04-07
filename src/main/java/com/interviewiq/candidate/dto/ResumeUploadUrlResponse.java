package com.interviewiq.candidate.dto;

public record ResumeUploadUrlResponse(
        /** Presigned S3 PUT URL. The client uploads the resume directly to this URL. */
        String uploadUrl,
        /** The S3 object key stored on the Candidate — needed for the completion callback. */
        String objectKey
) {}
