package com.interviewiq.auth.dto;

/**
 * Refresh request.
 *
 * <p>The token itself arrives in the HTTP-only {@code iiq_refresh} cookie rather
 * than this body (PRD v2.1 §7.1.1), so there is nothing left to validate here.
 * The record is retained as the service-layer carrier.
 */
public record RefreshRequest(String refreshToken) {}
