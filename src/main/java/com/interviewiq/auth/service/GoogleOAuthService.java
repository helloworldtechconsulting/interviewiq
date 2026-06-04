package com.interviewiq.auth.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.interviewiq.auth.config.SecurityProperties;
import com.interviewiq.auth.exception.InvalidTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * Verifies Google ID tokens issued by the {@code @react-oauth/google}
 * frontend library.
 *
 * <h2>ID-token flow</h2>
 * <ol>
 *   <li>Frontend calls Google's OAuth endpoint and receives a {@code credential}
 *       (a signed JWT — the ID token).</li>
 *   <li>Frontend POSTs the credential to our backend.</li>
 *   <li>This service verifies the JWT signature, expiry, {@code iss}, and
 *       {@code aud} claims using Google's public keys.</li>
 *   <li>On success, the caller receives the parsed {@link Payload} containing
 *       {@code sub} (subject), {@code email}, and {@code name}.</li>
 * </ol>
 *
 * <h2>Configuration</h2>
 * <p>Requires {@code app.security.google.client-id} to be set. In production,
 * inject via {@code APP_SECURITY_GOOGLE_CLIENT_ID}.
 *
 * <h2>Thread safety</h2>
 * <p>{@link GoogleIdTokenVerifier} is thread-safe; the singleton is safe to
 * share across concurrent requests.
 */
@Service
public class GoogleOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthService.class);

    private final GoogleIdTokenVerifier verifier;

    public GoogleOAuthService(SecurityProperties props) {
        String clientId = props.getGoogle().getClientId();
        this.verifier = new GoogleIdTokenVerifier.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(clientId))
                .build();
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Verifies a Google ID token and returns its payload.
     *
     * <p>The caller should extract:
     * <ul>
     *   <li>{@code payload.getSubject()} — stable Google account ID ({@code sub})</li>
     *   <li>{@code payload.getEmail()} — verified email address</li>
     *   <li>{@code (String) payload.get("name")} — display name</li>
     *   <li>{@code payload.getEmailVerified()} — true if Google has verified the email</li>
     * </ul>
     *
     * @param idTokenString the raw Google ID token JWT string from the frontend
     * @return parsed and verified {@link Payload}
     * @throws InvalidTokenException if the token is malformed, expired, or the
     *                               signature/audience check fails
     */
    public Payload verify(String idTokenString) {
        try {
            GoogleIdToken idToken = verifier.verify(idTokenString);
            if (idToken == null) {
                throw new InvalidTokenException("Google ID token verification failed.");
            }
            Payload payload = idToken.getPayload();
            log.debug("Google ID token verified: sub={} email={}", payload.getSubject(), payload.getEmail());
            return payload;
        } catch (InvalidTokenException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Google ID token verification error: {}", e.getMessage());
            throw new InvalidTokenException("Invalid or expired Google ID token.");
        }
    }
}
