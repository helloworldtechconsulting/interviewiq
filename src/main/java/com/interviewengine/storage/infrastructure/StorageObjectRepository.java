package com.interviewengine.storage.infrastructure;

import com.interviewengine.storage.domain.StorageObject;
import com.interviewengine.storage.domain.StorageObjectType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StorageObjectRepository extends JpaRepository<StorageObject, UUID> {

    Optional<StorageObject> findByBucketAndObjectKey(String bucket, String objectKey);

    /** Find all metadata records for a specific entity (e.g. all files for a candidate). */
    List<StorageObject> findAllByEntityIdAndObjectType(UUID entityId, StorageObjectType objectType);

    List<StorageObject> findAllByEntityId(UUID entityId);

    /** S3 cleanup reconciliation: all files for a company. */
    List<StorageObject> findAllByCompanyId(UUID companyId);
}
