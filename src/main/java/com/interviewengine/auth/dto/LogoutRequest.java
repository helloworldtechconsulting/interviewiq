package com.interviewengine.auth.dto;

/** Logout request. The token arrives in the HTTP-only cookie, not this body. */
public record LogoutRequest(String refreshToken) {}
