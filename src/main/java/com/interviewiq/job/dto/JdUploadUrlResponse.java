package com.interviewiq.job.dto;

public record JdUploadUrlResponse(
        /** Presigned S3 PUT URL. The client uploads the JD file directly to this URL. */
        String uploadUrl,
        /** The S3 object key stored on the JobOpening — needed for the completion callback. */
        String objectKey
) {}
