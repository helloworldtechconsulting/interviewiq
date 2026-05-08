package com.interviewiq.storage.domain;

/**
 * DB CHECK values for storage_objects.object_type.
 * Original values (V013): RESUME, JOB_DESCRIPTION, TRANSCRIPT, EVALUATION_EXPORT
 * Extended (V037): COMPANY_LOGO, RECORDING
 */
public enum StorageObjectType {
    RESUME,
    JOB_DESCRIPTION,
    TRANSCRIPT,
    EVALUATION_EXPORT,
    /** Company logo uploaded via CompanyController.getLogoUploadUrl(). Stored under companies.logo_s3_key. */
    COMPANY_LOGO,
    /** Session video recording uploaded by the Recall.ai bot. S3 lifecycle: auto-deleted after 7 days. */
    RECORDING
}
