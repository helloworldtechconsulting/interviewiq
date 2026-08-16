package com.interviewengine.storage.service;

import com.interviewengine.shared.config.ObjectStorageProperties;
import com.interviewengine.shared.exception.ValidationException;
import com.interviewengine.storage.domain.UploadKind;
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
 * Wrapper around S3-compatible object storage for pre-signed URL generation and
 * object lifecycle management.
 *
 * <p>Works against S3, GCS interop, Cloudflare R2, DigitalOcean Spaces, MinIO or
 * OCI — the difference is an endpoint override and path-style addressing, both
 * configured in {@link com.interviewengine.shared.config.ObjectStorageConfig}.
 *
 * <p>All operations short-circuit to stub responses when
 * {@link ObjectStorageProperties#isUseLocalStub()} is {@code true}. Stub URLs are recognisable
 * by the {@code ?stub=} query parameter and are not valid for real S3 requests.
 *
 * <p>Objects are always stored in the bucket configured via {@code app.storage.bucket}.
 * Callers supply only the <em>object key</em> (the path within the bucket).
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final S3Client    s3Client;
    private final S3Presigner s3Presigner;
    private final ObjectStorageProperties props;

    public StorageService(S3Client s3Client, S3Presigner s3Presigner, ObjectStorageProperties props) {
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
            log.info("[STORAGE STUB] presignedUpload bucket={} key={} contentType={}", props.getBucket(), objectKey, contentType);
            return "http://localhost:4566/" + props.getBucket() + "/" + objectKey + "?stub=upload";
        }

        PutObjectPresignRequest request = PutObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .putObjectRequest(PutObjectRequest.builder()
                        .bucket(props.getBucket())
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
            log.info("[STORAGE STUB] presignedDownload bucket={} key={}", props.getBucket(), objectKey);
            return "http://localhost:4566/" + props.getBucket() + "/" + objectKey + "?stub=download";
        }

        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(props.getBucket())
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
            log.info("[STORAGE STUB] downloadObject bucket={} key={}", props.getBucket(), objectKey);
            return new byte[0];
        }

        ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(
                GetObjectRequest.builder()
                        .bucket(props.getBucket())
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
     * <p>Returns what the object actually is, not what the client said it was.
     * Callers persist these values rather than the request payload's — the whole
     * point of the check is that the client's claims are not authoritative.
     *
     * @param objectKey the key the client has just uploaded to
     * @param kind      the upload class whose limits apply
     * @return the verified size and content type as reported by the bucket
     * @throws ValidationException if the object is missing, too large, or of a
     *                             content type outside the allow-list
     */
    public VerifiedObject verifyUploadedObject(String objectKey, UploadKind kind) {
        if (props.isUseLocalStub()) {
            log.info("[STORAGE STUB] verifyUploadedObject key={} kind={}", objectKey, kind);
            return VerifiedObject.stub();
        }

        HeadObjectResponse head;
        try {
            head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(props.getBucket())
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
        return new VerifiedObject(size, UploadKind.normaliseContentType(head.contentType()));
    }

    /**
     * What a verified object turned out to be, read back from the bucket.
     *
     * <p>In stub mode there is no object to inspect, so the size is zero and the
     * content type null. Persisting those is correct — a stubbed upload genuinely
     * has no verified size, and recording a fabricated one would make local rows
     * indistinguishable from real ones.
     */
    public record VerifiedObject(long sizeBytes, String contentType) {
        static VerifiedObject stub() {
            return new VerifiedObject(0L, null);
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
            log.info("[STORAGE STUB] deleteObject bucket={} key={}", props.getBucket(), objectKey);
            return;
        }

        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(props.getBucket())
                .key(objectKey)
                .build());
    }
}
