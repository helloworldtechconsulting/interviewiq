package com.interviewengine.storage.service;

import com.interviewengine.shared.config.ObjectStorageProperties;
import com.interviewengine.storage.domain.StorageObject;
import com.interviewengine.storage.domain.StorageObjectType;
import com.interviewengine.storage.infrastructure.StorageObjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link StorageObjectRecorder}, the class that finally puts rows in
 * {@code storage_objects} — a table that had existed since V013 and never held
 * one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StorageObjectRecorderTest {

    private static final String BUCKET = "interviewengine-prod";

    @Mock StorageObjectRepository repository;

    private StorageObjectRecorder recorder() {
        ObjectStorageProperties props = new ObjectStorageProperties();
        props.setBucket(BUCKET);
        return new StorageObjectRecorder(repository, props);
    }

    @Test
    void recordsTheVerifiedSizeAndContentTypeRatherThanAnythingTheClientSent() {
        UUID companyId = UUID.randomUUID();
        UUID entityId  = UUID.randomUUID();
        when(repository.findByBucketAndObjectKey(any(), any())).thenReturn(Optional.empty());

        recorder().record(companyId, entityId, StorageObjectType.RESUME,
                "resumes/c/e/file.pdf",
                new StorageService.VerifiedObject(4_096L, "application/pdf"));

        ArgumentCaptor<StorageObject> saved = ArgumentCaptor.forClass(StorageObject.class);
        verify(repository).save(saved.capture());

        StorageObject row = saved.getValue();
        assertThat(row.getCompanyId()).isEqualTo(companyId);
        assertThat(row.getEntityId()).isEqualTo(entityId);
        assertThat(row.getBucket()).isEqualTo(BUCKET);
        assertThat(row.getObjectKey()).isEqualTo("resumes/c/e/file.pdf");
        assertThat(row.getObjectType()).isEqualTo(StorageObjectType.RESUME);
        assertThat(row.getSizeBytes()).isEqualTo(4_096L);
        assertThat(row.getContentType()).isEqualTo("application/pdf");
    }

    /**
     * Confirm is client-driven and clients retry. Two confirmations of the same
     * upload must leave one row — duplicates would corrupt any storage total
     * computed from this table.
     */
    @Test
    void reConfirmingTheSameUploadUpdatesTheExistingRow() {
        UUID companyId = UUID.randomUUID();
        UUID entityId  = UUID.randomUUID();

        StorageObject existing = new StorageObject();
        existing.setId(UUID.randomUUID());
        existing.setCompanyId(companyId);
        existing.setBucket(BUCKET);
        existing.setObjectKey("recordings/c/s/file.webm");
        existing.setSizeBytes(1_000L);
        when(repository.findByBucketAndObjectKey(BUCKET, "recordings/c/s/file.webm"))
                .thenReturn(Optional.of(existing));

        recorder().record(companyId, entityId, StorageObjectType.RECORDING,
                "recordings/c/s/file.webm",
                new StorageService.VerifiedObject(140_000_000L, "video/webm"));

        verify(repository, times(1)).save(existing);
        assertThat(existing.getSizeBytes()).isEqualTo(140_000_000L);
    }

    /**
     * The object is already in the bucket and the owning entity already points at
     * it by the time this runs. A failure to write the bookkeeping row is a
     * reporting gap that a bucket sweep can repair; propagating it would roll back
     * the confirm and turn a successful upload into a failed one.
     */
    @Test
    void aRepositoryFailureDoesNotFailTheUpload() {
        when(repository.findByBucketAndObjectKey(any(), any()))
                .thenThrow(new RuntimeException("connection reset"));

        assertThatCode(() -> recorder().record(
                UUID.randomUUID(), UUID.randomUUID(), StorageObjectType.COMPANY_LOGO,
                "logos/c/c/file.png",
                new StorageService.VerifiedObject(2_048L, "image/png")))
                .doesNotThrowAnyException();
    }

    /**
     * In stub mode there is no object to inspect. Recording a zero size is honest;
     * fabricating one would make local rows indistinguishable from real ones.
     */
    @Test
    void stubModeRecordsNoFabricatedMetadata() {
        when(repository.findByBucketAndObjectKey(any(), any())).thenReturn(Optional.empty());

        recorder().record(UUID.randomUUID(), UUID.randomUUID(), StorageObjectType.RESUME,
                "resumes/c/e/file.pdf", new StorageService.VerifiedObject(0L, null));

        ArgumentCaptor<StorageObject> saved = ArgumentCaptor.forClass(StorageObject.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSizeBytes()).isZero();
        assertThat(saved.getValue().getContentType()).isNull();
    }
}
