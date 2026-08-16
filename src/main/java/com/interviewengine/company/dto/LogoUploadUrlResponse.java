package com.interviewengine.company.dto;

/**
 * Pre-signed PUT URL for a company logo, plus the key the client must send back
 * to {@code POST /companies/me/logo-upload-confirm}.
 *
 * <p>The key is returned rather than left implicit so the client has nothing to
 * construct. It is still re-validated on confirm — handing it out does not make
 * it trusted on the way back.
 *
 * @param uploadUrl pre-signed PUT URL, valid for 15 minutes
 * @param objectKey server-derived object key to echo back on confirm
 */
public record LogoUploadUrlResponse(
        String uploadUrl,
        String objectKey
) {}
