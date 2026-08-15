package com.interviewiq.storage.service;

import com.interviewiq.shared.exception.AuthorizationException;
import com.interviewiq.shared.exception.ValidationException;
import com.interviewiq.storage.domain.UploadKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Builds and validates pre-signed-upload object keys.
 *
 * <p>Two ship-blocking defects meet here, and they compound each other: uploads had
 * no size or MIME validation, and {@code upload-confirm} accepted whatever object
 * key the client sent. Together that is a cross-tenant object-access path — a caller
 * could confirm an upload against another company's key and read it back through
 * their own entity's download URL.
 *
 * <p>The fix is that a key is only ever <em>derived</em> here, from the authenticated
 * company and the target entity. {@link #validateOwnedKey} exists for the confirm
 * step, which still receives a key over the wire: it checks that the key sits inside
 * the caller's own tenant and entity namespace before anything is persisted. Both
 * halves matter — deriving keys stops us minting bad ones, validating stops the
 * client asserting one.
 *
 * @see UploadKind for the per-kind MIME allow-list and size ceiling
 */
@Service
public class UploadKeyService {

    private static final Logger log = LoggerFactory.getLogger(UploadKeyService.class);

    /**
     * Derives the object key for an upload and checks the content type against the
     * kind's allow-list.
     *
     * @param kind        what is being uploaded — sets the namespace, MIME allow-list and size cap
     * @param companyId   the authenticated tenant
     * @param entityId    the entity the object belongs to (candidate, job, session, company)
     * @param contentType the client-declared MIME type
     * @return a server-derived key of the form {@code prefix/companyId/entityId/file.ext}
     * @throws ValidationException if the content type is not permitted for this kind
     */
    public String deriveKey(UploadKind kind, UUID companyId, UUID entityId, String contentType) {
        if (!kind.permits(contentType)) {
            throw new ValidationException(
                    "Unsupported file type '" + UploadKind.normaliseContentType(contentType)
                            + "'. Allowed: " + String.join(", ", kind.getAllowedContentTypes()) + ".");
        }
        return ownedPrefix(kind, companyId, entityId) + "file." + kind.extensionFor(contentType);
    }

    /**
     * Validates a key supplied by a client at confirm time against the tenant and
     * entity it claims to belong to.
     *
     * <p>Rejects anything outside {@code prefix/companyId/entityId/}, which covers
     * both the cross-tenant case and path traversal via {@code ..} segments.
     *
     * @return the key, unchanged, when it is legitimately owned
     * @throws ValidationException  if the key is blank or malformed
     * @throws AuthorizationException if the key belongs to another tenant or entity
     */
    public String validateOwnedKey(UploadKind kind, UUID companyId, UUID entityId, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new ValidationException("objectKey is required.");
        }
        if (objectKey.contains("..") || objectKey.startsWith("/")) {
            throw new ValidationException("objectKey is malformed.");
        }

        String expectedPrefix = ownedPrefix(kind, companyId, entityId);
        if (!objectKey.startsWith(expectedPrefix)) {
            log.warn("Rejected cross-tenant upload confirmation: kind={} companyId={} entityId={} key={}",
                    kind, companyId, entityId, objectKey);
            throw new AuthorizationException("This object key does not belong to the requested resource.");
        }
        return objectKey;
    }

    private String ownedPrefix(UploadKind kind, UUID companyId, UUID entityId) {
        return kind.getKeyPrefix() + "/" + companyId + "/" + entityId + "/";
    }
}
