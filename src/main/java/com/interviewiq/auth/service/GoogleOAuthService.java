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
