package com.interviewiq.storage.service;

import com.interviewiq.shared.config.ObjectStorageProperties;
import com.interviewiq.storage.domain.StorageObject;
import com.interviewiq.storage.domain.StorageObjectType;
import com.interviewiq.storage.infrastructure.StorageObjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes the {@code storage_objects} metadata row for a confirmed upload.
 *
 * <p>The table has existed since {@code V013} and, until this class, had never
 * held a row. That is worth being explicit about, because the emptiness was not
 * harmless: the table exists so that the object store can be reconciled against
 * something. Without it there is no answer to "what does this company have in
 * the bucket, and is any of it orphaned?" other than listing the bucket by
 * prefix and hoping the naming convention has never changed.
 *
 * <p>Two properties make the rows worth trusting:
 *
 * <ul>
 *   <li><strong>Recorded from the verified object, not the request.</strong> Size
 *       and content type come from {@link StorageService.VerifiedObject}, which is
 *       read back from the bucket by {@code HeadObject}. A client that lies about
 *       either in its confirm payload does not get its lie persisted.</li>
 *   <li><strong>Idempotent on (bucket, key).</strong> Confirm is a client-driven
 *       endpoint and clients retry. Re-confirming the same upload updates the
 *       existing row rather than accumulating duplicates, which would corrupt any
 *       storage total computed from this table.</li>
 * </ul>
 *
 * <p>Runs in its own transaction. Recording metadata must never be the reason a
 * confirm fails — the object is already in the bucket and the owning entity has
 * been updated, so rolling those back over a bookkeeping row would turn a
 * successful upload into a failed one.
 */
@Service
public class StorageObjectRecorder {

    private static final Logger log = LoggerFactory.getLogger(StorageObjectRecorder.class);

    private final StorageObjectRepository repository;
    private final ObjectStorageProperties props;

    public StorageObjectRecorder(StorageObjectRepository repository, ObjectStorageProperties props) {
        this.repository = repository;
        this.props      = props;
    }

    /**
     * Records (or refreshes) the metadata row for an object that has just passed
     * verification.
     *
     * @param companyId the owning tenant
     * @param entityId  the candidate, job, session or company the object belongs to
     * @param type      what the object is, for reconciliation and retention rules
     * @param objectKey the verified, ownership-checked key
     * @param verified  size and content type as read back from the bucket
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID companyId,
                       UUID entityId,
                       StorageObjectType type,
                       String objectKey,
                       StorageService.VerifiedObject verified) {
        try {
            StorageObject row = repository
                    .findByBucketAndObjectKey(props.getBucket(), objectKey)
                    .orElseGet(StorageObject::new);

            if (row.getId() == null) {
                row.setCompanyId(companyId);
                row.setBucket(props.getBucket());
                row.setObjectKey(objectKey);
            }
            row.setObjectType(type);
            row.setEntityId(entityId);
            row.setSizeBytes(verified.sizeBytes());
            row.setContentType(verified.contentType());

            repository.save(row);
        } catch (RuntimeException e) {
            // Deliberately swallowed. The upload succeeded and the owning entity
            // already points at the key; losing the reconciliation row is a
            // reporting gap, not a data-loss event, and is recoverable by a
            // bucket sweep. Failing the confirm here would lose the upload.
            log.error("Could not record storage object metadata: key={} type={} companyId={}",
                    objectKey, type, companyId, e);
        }
    }
}
