package com.interviewengine.shared.exception;

/**
 * Thrown when an AWS S3 (or compatible storage) operation fails — upload,
 * presigned URL generation, object deletion, etc.
 *
 * <p>Inherits HTTP 502 Bad Gateway from {@link ExternalServiceException}.
 */
public class StorageServiceException extends ExternalServiceException {

    public StorageServiceException(String message) {
        super(message);
    }

    public StorageServiceException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getErrorCode() {
        return com.interviewengine.shared.dto.ApiErrorResponse.ErrorCode.STORAGE_SERVICE_ERROR;
    }
}
