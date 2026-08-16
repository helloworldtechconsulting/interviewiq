package com.interviewengine.email.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewengine.email.domain.EmailEvent;
import com.interviewengine.email.domain.EmailStatus;
import com.interviewengine.email.domain.SuppressionReason;
import com.interviewengine.email.infrastructure.EmailEventRepository;
import com.interviewengine.email.service.parser.BrevoNotificationParser;
import com.interviewengine.email.service.parser.PostmarkNotificationParser;
import com.interviewengine.email.service.parser.ResendNotificationParser;
import com.interviewengine.shared.config.MailProperties;
import com.interviewengine.shared.exception.ValidationException;
import com.interviewengine.webhook.domain.WebhookProvider;
import com.interviewengine.webhook.infrastructure.WebhookEventRepository;
import com.interviewengine.webhook.service.WebhookSignatureVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EmailWebhookService} — the inbound bounce/complaint path
 * (INTIQ-32).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailWebhookServiceTest {

    private static final String SECRET = "smtp-webhook-secret";

    @Mock WebhookEventRepository  webhookEventRepository;
    @Mock EmailEventRepository    emailEventRepository;
    @Mock EmailSuppressionService suppressionService;

    private MailProperties      mailProperties;
    private EmailWebhookService service;

    @BeforeEach
    void setUp() {
        mailProperties = new MailProperties();
        mailProperties.setWebhookSecret(SECRET);
        mailProperties.setWebhookProvider("postmark");

        service = new EmailWebhookService(
                webhookEventRepository,
                emailEventRepository,
                suppressionService,
                new WebhookSignatureVerifier(),
                mailProperties,
                new ObjectMapper(),
                List.of(new PostmarkNotificationParser(),
                        new ResendNotificationParser(),
                        new BrevoNotificationParser()));

        when(webhookEventRepository.existsByProviderAndIdempotencyKey(any(), anyString())).thenReturn(false);
        when(emailEventRepository.findTop10ByRecipientEmailAndStatusOrderByCreatedAtDesc(anyString(), any()))
                .thenReturn(List.of());
    }

    private static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void post(String body) {
        service.handle(body.getBytes(StandardCharsets.UTF_8), sign(body));
    }

    // =========================================================================
    // Signature verification — the endpoint is permitAll, so this is the gate
    // =========================================================================

    @Test
    void aForgedSignatureIsRejectedAndNothingIsSuppressed() {
        String body = """
                {"RecordType":"Bounce","Type":"HardBounce","Email":"a@example.com","ID":"1"}""";

        assertThatThrownBy(() ->
                service.handle(body.getBytes(StandardCharsets.UTF_8), sign(body + "tampered")))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(suppressionService);
    }

    /**
     * The worst failure mode this endpoint has: anyone who can POST to it could
     * suppress any address — silently cutting a candidate off from every future
     * invite — if a blank secret meant "verification disabled".
     */
    @Test
    void anUnconfiguredSecretRejectsRatherThanAcceptsEverything() {
        mailProperties.setWebhookSecret("");
        String body = """
                {"RecordType":"Bounce","Type":"HardBounce","Email":"a@example.com","ID":"1"}""";

        assertThatThrownBy(() -> service.handle(body.getBytes(StandardCharsets.UTF_8), "anything"))
                .isInstanceOf(ValidationException.class);

        verifyNoInteractions(suppressionService);
    }

    /**
     * An unset provider must reject too. Falling back to a default parser would
     * classify every real bounce as IGNORED — the endpoint would return 200 and
     * do nothing, which is the hardest kind of failure to notice.
     */
    @Test
    void anUnconfiguredProviderRejectsRatherThanGuessing() {
        mailProperties.setWebhookProvider("");
        String body = """
                {"RecordType":"Bounce","Type":"HardBounce","Email":"a@example.com","ID":"1"}""";

        assertThatThrownBy(() -> service.handle(body.getBytes(StandardCharsets.UTF_8), sign(body)))
                .isInstanceOf(ValidationException.class);
    }

    // =========================================================================
    // Hardness — the distinction the whole feature turns on
    // =========================================================================

    @Test
    void aHardBounceSuppressesTheAddress() {
        post("""
                {"RecordType":"Bounce","Type":"HardBounce","Email":"Gone@Example.com",
                 "ID":"77","Description":"mailbox does not exist"}""");

        verify(suppressionService).suppress(
                eq("gone@example.com"), eq(SuppressionReason.BOUNCE), anyString(), anyString());
    }

    /**
     * A full mailbox on Monday is a deliverable address on Friday. Suppressing
     * on a soft bounce would cut a candidate off for a condition that resolves
     * itself, and no second notification would ever arrive to undo it.
     */
    @Test
    void aSoftBounceDoesNotSuppress() {
        post("""
                {"RecordType":"Bounce","Type":"SoftBounce","Email":"full@example.com",
                 "ID":"78","Description":"mailbox full"}""");

        verify(suppressionService, never()).suppress(anyString(), any(), anyString(), anyString());
    }

    /** An unrecognised bounce type defaults to soft — see PostmarkNotificationParser. */
    @Test
    void anUnknownBounceTypeIsTreatedAsSoft() {
        post("""
                {"RecordType":"Bounce","Type":"SomethingPostmarkAddedLastWeek",
                 "Email":"x@example.com","ID":"79"}""");

        verify(suppressionService, never()).suppress(anyString(), any(), anyString(), anyString());
    }

    @Test
    void aSpamComplaintSuppressesWithTheComplaintReason() {
        post("""
                {"RecordType":"SpamComplaint","Email":"annoyed@example.com","ID":"80"}""");

        verify(suppressionService).suppress(
                eq("annoyed@example.com"), eq(SuppressionReason.COMPLAINT), anyString(), anyString());
    }

    /**
     * Providers send opens, clicks and deliveries to the same endpoint. These
     * must return 200 and do nothing — throwing would put the provider into a
     * retry loop over an event we never wanted.
     */
    @Test
    void anUnrelatedEventTypeIsIgnoredWithoutError() {
        post("""
                {"RecordType":"Open","Email":"reader@example.com","ID":"81"}""");

        verify(suppressionService, never()).suppress(anyString(), any(), anyString(), anyString());
    }

    // =========================================================================
    // Idempotency and attribution
    // =========================================================================

    @Test
    void aRedeliveredNotificationIsProcessedOnlyOnce() {
        when(webhookEventRepository.existsByProviderAndIdempotencyKey(eq(WebhookProvider.SYSTEM), anyString()))
                .thenReturn(true);

        post("""
                {"RecordType":"Bounce","Type":"HardBounce","Email":"a@example.com","ID":"82"}""");

        verify(suppressionService, never()).suppress(anyString(), any(), anyString(), anyString());
    }

    /** The bounce should mark the send it refers to, so support can see it. */
    @Test
    void theMostRecentSentEmailIsMarkedBounced() {
        EmailEvent sent = new EmailEvent();
        sent.setRecipientEmail("gone@example.com");
        sent.setStatus(EmailStatus.SENT);
        when(emailEventRepository.findTop10ByRecipientEmailAndStatusOrderByCreatedAtDesc(
                "gone@example.com", EmailStatus.SENT)).thenReturn(List.of(sent));

        post("""
                {"RecordType":"Bounce","Type":"HardBounce","Email":"gone@example.com","ID":"83"}""");

        ArgumentCaptor<EmailEvent> saved = ArgumentCaptor.forClass(EmailEvent.class);
        verify(emailEventRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(EmailStatus.BOUNCED);
    }

    /** No matching send is normal, not an error — the suppression still stands. */
    @Test
    void aBounceWithNoMatchingSendStillSuppresses() {
        post("""
                {"RecordType":"Bounce","Type":"HardBounce","Email":"orphan@example.com","ID":"84"}""");

        verify(suppressionService).suppress(
                eq("orphan@example.com"), eq(SuppressionReason.BOUNCE), anyString(), anyString());
        verify(emailEventRepository, never()).save(any());
    }

    // =========================================================================
    // The other two provider shapes
    // =========================================================================

    @Test
    void resendPermanentBounceSuppressesAndTransientDoesNot() {
        mailProperties.setWebhookProvider("resend");

        post("""
                {"type":"email.bounced","data":{"email_id":"e1","to":["hard@example.com"],
                 "bounce":{"type":"Permanent","message":"no such user"}}}""");
        verify(suppressionService).suppress(
                eq("hard@example.com"), eq(SuppressionReason.BOUNCE), anyString(), anyString());

        post("""
                {"type":"email.bounced","data":{"email_id":"e2","to":["soft@example.com"],
                 "bounce":{"type":"Transient","message":"try later"}}}""");
        verify(suppressionService, never()).suppress(
                eq("soft@example.com"), any(), anyString(), anyString());
    }

    /**
     * Resend can address one message to several recipients. Each needs its own
     * idempotency key, or all but the first are dropped as duplicates and only
     * one of the bounced addresses gets suppressed.
     */
    @Test
    void aMultiRecipientResendBounceSuppressesEveryAddress() {
        mailProperties.setWebhookProvider("resend");

        post("""
                {"type":"email.bounced","data":{"email_id":"e3",
                 "to":["one@example.com","two@example.com"],
                 "bounce":{"type":"Permanent","message":"no such user"}}}""");

        verify(suppressionService).suppress(eq("one@example.com"), any(), anyString(), anyString());
        verify(suppressionService).suppress(eq("two@example.com"), any(), anyString(), anyString());
    }

    @Test
    void brevoHardBounceAndSpamAreBothActedOn() {
        mailProperties.setWebhookProvider("brevo");

        post("""
                {"event":"hard_bounce","email":"b1@example.com","message-id":"m1","reason":"unknown"}""");
        verify(suppressionService).suppress(
                eq("b1@example.com"), eq(SuppressionReason.BOUNCE), anyString(), anyString());

        post("""
                {"event":"spam","email":"b2@example.com","message-id":"m2"}""");
        verify(suppressionService).suppress(
                eq("b2@example.com"), eq(SuppressionReason.COMPLAINT), anyString(), anyString());
    }
}
