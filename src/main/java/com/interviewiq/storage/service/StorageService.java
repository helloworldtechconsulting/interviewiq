package com.interviewiq.storage.service;

import com.interviewiq.shared.config.AwsProperties;
import com.interviewiq.shared.exception.ValidationException;
import com.interviewiq.storage.domain.UploadKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

/**
 * Wrapper around AWS S3 for presigned URL generation and object lifecycle management.
 *
 * <p>All operations short-circuit to stub responses when
 * {@link AwsProperties#isUseLocalStub()} is {@code true}. Stub URLs are recognisable
 * by the {@code ?stub=} query parameter and are not valid for real S3 requests.
 *
 * <p>Objects are always stored in the bucket configured via {@code app.aws.s3-bucket}.
 * Callers supply only the <em>object key</em> (the path within the bucket).
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final S3Client    s3Client;
    private final S3Presigner s3Presigner;
    private final AwsProperties props;

    public StorageService(S3Client s3Client, S3Presigner s3Presigner, AwsProperties props) {
        this.s3Client    = s3Client;
        this.s3Presigner = s3Presigner;
        this.props       = props;
    }

    /**
     * Generates a presigned PUT URL that allows the client to upload an object directly
     * to S3 without routing the file through the application server.
     *
     * @param objectKey   the S3 object key (e.g. {@code resumes/company-id/candidate-id.pdf})
     * @param contentType the MIME type the client must set in the {@code Content-Type} header
     * @param expiry      how long the URL remains valid (max 7 days for presigned PUT)
     * @return presigned URL string
     */
    public String generatePresignedUploadUrl(String objectKey, String contentType, Duration expiry) {
        if (props.isUseLocalStub()) {
            log.info("[S3 STUB] presignedUpload bucket={} key={} contentType={}", props.getS3Bucket(), objectKey, contentType);
            return "http://localhost:4566/" + props.getS3Bucket() + "/" + objectKey + "?stub=upload";
        }

        PutObjectPresignRequest request = PutObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .putObjectRequest(PutObjectRequest.builder()
                        .bucket(props.getS3Bucket())
                        .key(objectKey)
                        .contentType(contentType)
                        .build())
                .build();

        return s3Presigner.presignPutObject(request).url().toString();
    }

    /**
     * Generates a presigned GET URL that allows the client to download an object directly
     * from S3 without routing the download through the application server.
     *
     * @param objectKey the S3 object key
     * @param expiry    how long the URL remains valid
     * @return presigned URL string
     */
    public String generatePresignedDownloadUrl(String objectKey, Duration expiry) {
        if (props.isUseLocalStub()) {
            log.info("[S3 STUB] presignedDownload bucket={} key={}", props.getS3Bucket(), objectKey);
            return "http://localhost:4566/" + props.getS3Bucket() + "/" + objectKey + "?stub=download";
        }

        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(props.getS3Bucket())
                        .key(objectKey)
                        .build())
                .build();

        return s3Presigner.presignGetObject(request).url().toString();
    }

    /**
     * Downloads the raw bytes of an S3 object. Used by async extraction workers that
     * need to pass file content to Apache Tika without buffering through a temp file.
     *
     * <p>Returns an empty array in stub mode — callers should treat empty content as a
     * signal to generate placeholder text rather than attempting Tika parsing.
     *
     * @param objectKey the S3 object key to download
     * @return raw file bytes, or {@code byte[0]} in stub mode
     */
    public byte[] downloadObject(String objectKey) {
        if (props.isUseLocalStub()) {
            log.info("[S3 STUB] downloadObject bucket={} key={}", props.getS3Bucket(), objectKey);
            return new byte[0];
        }

        ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(
                GetObjectRequest.builder()
                        .bucket(props.getS3Bucket())
                        .key(objectKey)
                        .build());
        return response.asByteArray();
    }

    /**
     * Verifies that an uploaded object actually conforms to its {@link UploadKind}'s
     * size ceiling and MIME allow-list, and deletes it if it does not.
     *
     * <p>A pre-signed PUT cannot enforce a maximum size — the signature covers the
     * key and content type, not the body length, so a client that has been handed a
     * URL can upload a 5 GB file to it. The check therefore has to happen after the
     * fact, at confirm time, against what is really in the bucket rather than what
     * the client claimed. Anything out of bounds is deleted immediately, so a
     * rejected upload cannot linger and bill us for storage.
     *
     * <p>Skipped in stub mode, where no object exists to inspect.
     *
     * @param objectKey the key the client has just uploaded to
     * @param kind      the upload class whose limits apply
     * @throws ValidationException if the object is missing, too large, or of a
     *                             content type outside the allow-list
     */
    public void verifyUploadedObject(String objectKey, UploadKind kind) {
        if (props.isUseLocalStub()) {
            log.info("[S3 STUB] verifyUploadedObject key={} kind={}", objectKey, kind);
            return;
        }

        HeadObjectResponse head;
        try {
            head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(props.getS3Bucket())
                    .key(objectKey)
                    .build());
        } catch (NoSuchKeyException e) {
            throw new ValidationException("No uploaded file was found. Please upload the file before confirming.");
        }

        long size = head.contentLength() == null ? 0L : head.contentLength();
        if (size <= 0) {
            deleteObject(objectKey);
            throw new ValidationException("The uploaded file is empty.");
        }
        if (size > kind.getMaxBytes()) {
            log.warn("Rejected oversized upload: key={} kind={} size={} max={}",
                    objectKey, kind, size, kind.getMaxBytes());
            deleteObject(objectKey);
            throw new ValidationException("File exceeds the maximum size of " + kind.maxSizeLabel() + ".");
        }
        if (!kind.permits(head.contentType())) {
            log.warn("Rejected upload with disallowed content type: key={} kind={} contentType={}",
                    objectKey, kind, head.contentType());
            deleteObject(objectKey);
            throw new ValidationException(
                    "Unsupported file type '" + UploadKind.normaliseContentType(head.contentType())
                            + "'. Allowed: " + String.join(", ", kind.getAllowedContentTypes()) + ".");
        }
    }

    /**
     * Deletes an object from S3. Safe to call with keys that do not exist
     * (S3 DELETE is idempotent).
     *
     * @param objectKey the S3 object key to delete
     */
    public void deleteObject(String objectKey) {
        if (props.isUseLocalStub()) {
            log.info("[S3 STUB] deleteObject bucket={} key={}", props.getS3Bucket(), objectKey);
            return;
        }

        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(props.getS3Bucket())
                .key(objectKey)
                .build());
    }
}
