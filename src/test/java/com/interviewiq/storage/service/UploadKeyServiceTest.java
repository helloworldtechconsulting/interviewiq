package com.interviewiq.storage.service;

import com.interviewiq.shared.exception.AuthorizationException;
import com.interviewiq.shared.exception.ValidationException;
import com.interviewiq.storage.domain.UploadKind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the cross-tenant object-access path called out as ship-blocking in the
 * PRD v2.1 handoff notes: {@code upload-confirm} previously accepted an arbitrary
 * client-supplied object key.
 */
class UploadKeyServiceTest {

    private final UploadKeyService service = new UploadKeyService();

    private final UUID companyId = UUID.randomUUID();
    private final UUID entityId  = UUID.randomUUID();

    // =========================================================================
    // Key derivation
    // =========================================================================

    @Test
    void deriveKey_placesObjectInTenantAndEntityNamespace() {
        String key = service.deriveKey(UploadKind.RESUME, companyId, entityId, "application/pdf");

        assertThat(key).isEqualTo("resumes/" + companyId + "/" + entityId + "/file.pdf");
    }

    @Test
    void deriveKey_toleratesContentTypeParameters() {
        String key = service.deriveKey(
                UploadKind.JOB_DESCRIPTION, companyId, entityId, "application/pdf; charset=utf-8");

        assertThat(key).endsWith("file.pdf");
    }

    @Test
    void deriveKey_rejectsContentTypeOutsideAllowList() {
        assertThatThrownBy(() ->
                service.deriveKey(UploadKind.RESUME, companyId, entityId, "application/x-msdownload"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unsupported file type");
    }

    // =========================================================================
    // Ownership validation at confirm time
    // =========================================================================

    @Test
    void validateOwnedKey_acceptsKeyInsideCallersOwnNamespace() {
        String key = service.deriveKey(UploadKind.RESUME, companyId, entityId, "application/pdf");

        assertThat(service.validateOwnedKey(UploadKind.RESUME, companyId, entityId, key))
                .isEqualTo(key);
    }

    @Test
    void validateOwnedKey_rejectsKeyBelongingToAnotherCompany() {
        UUID otherCompany = UUID.randomUUID();
        String victimKey = service.deriveKey(UploadKind.RESUME, otherCompany, entityId, "application/pdf");

        assertThatThrownBy(() ->
                service.validateOwnedKey(UploadKind.RESUME, companyId, entityId, victimKey))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void validateOwnedKey_rejectsKeyBelongingToAnotherEntityInSameCompany() {
        UUID otherEntity = UUID.randomUUID();
        String siblingKey = service.deriveKey(UploadKind.RESUME, companyId, otherEntity, "application/pdf");

        assertThatThrownBy(() ->
                service.validateOwnedKey(UploadKind.RESUME, companyId, entityId, siblingKey))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void validateOwnedKey_rejectsKeyFromADifferentUploadKind() {
        String jdKey = service.deriveKey(UploadKind.JOB_DESCRIPTION, companyId, entityId, "application/pdf");

        assertThatThrownBy(() ->
                service.validateOwnedKey(UploadKind.RESUME, companyId, entityId, jdKey))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void validateOwnedKey_rejectsPathTraversal() {
        String traversal = "resumes/" + companyId + "/" + entityId + "/../../other/file.pdf";

        assertThatThrownBy(() ->
                service.validateOwnedKey(UploadKind.RESUME, companyId, entityId, traversal))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void validateOwnedKey_rejectsBlankKey() {
        assertThatThrownBy(() ->
                service.validateOwnedKey(UploadKind.RESUME, companyId, entityId, "  "))
                .isInstanceOf(ValidationException.class);
    }
}
