package com.interviewiq.email.service;

import com.interviewiq.auth.domain.OtpPurpose;
import com.interviewiq.email.domain.EmailEvent;
import com.interviewiq.email.domain.EmailStatus;
import com.interviewiq.email.infrastructure.EmailEventRepository;
import com.interviewiq.shared.config.AwsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ses.model.SesException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Sends outbound emails via AWS SES and persists delivery records in {@code email_events}.
 *
 * <p>Failures are logged and swallowed — a failed email never aborts the calling
 * operation. Callers rely on the {@code FAILED} status in {@code email_events} and
 * the re-send endpoints to recover from transient SES errors.
 *
 * <p>All operations short-circuit to stub log output when
 * {@link AwsProperties#isUseLocalStub()} is {@code true}.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private static final String OTP_TEMPLATE = """
            <html><body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
            <h2>%s</h2>
            <p>%s</p>
            <div style="background: #f4f4f4; padding: 20px; text-align: center; border-radius: 8px; margin: 20px 0;">
              <span style="font-size: 32px; letter-spacing: 8px; font-weight: bold; color: #333;">%s</span>
            </div>
            <p style="color: #888; font-size: 12px;">This code expires in 10 minutes. If you didn't request this, ignore this email.</p>
            </body></html>
            """;

    private static final String INVITE_TEMPLATE = """
            <html><body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
            <h2>You've been invited to interview at %s</h2>
            <p>Hi %s,</p>
            <p>You have been invited to participate in an AI-powered interview on InterviewIQ.</p>
            <p style="margin: 30px 0;">
              <a href="%s" style="background: #4F46E5; color: white; padding: 14px 28px;
                 text-decoration: none; border-radius: 6px; font-size: 16px;">
                Accept Interview Invitation
              </a>
            </p>
            <p style="color: #888; font-size: 12px;">This invite link expires in 7 days.</p>
            </body></html>
            """;

    private final SesClient              sesClient;
    private final AwsProperties          props;
    private final EmailEventRepository   emailEventRepository;

    public EmailService(SesClient sesClient,
                        AwsProperties props,
                        EmailEventRepository emailEventRepository) {
        this.sesClient           = sesClient;
        this.props               = props;
        this.emailEventRepository = emailEventRepository;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Sends an OTP email for the given purpose and persists an {@link EmailEvent}.
     *
     * @param to      recipient email (must be lowercase)
     * @param purpose determines the subject line and body preamble
     * @param otp     the raw 6-digit code to include in the email
     * @param companyId nullable — null before company context is established
     * @param userId    nullable — null before user context is established
     */
    @Transactional
    public void sendOtpEmail(String to, OtpPurpose purpose, String otp, UUID companyId, UUID userId) {
        String subject = switch (purpose) {
            case EMAIL_VERIFICATION -> "Verify your InterviewIQ account";
            case PASSWORD_RESET     -> "Reset your InterviewIQ password";
            default                 -> "Your InterviewIQ verification code";
        };

        String heading = switch (purpose) {
            case EMAIL_VERIFICATION -> "Email Verification";
            case PASSWORD_RESET     -> "Password Reset";
            default                 -> "Verification Code";
        };

        String preamble = switch (purpose) {
            case EMAIL_VERIFICATION -> "Use the code below to verify your email address:";
            case PASSWORD_RESET     -> "Use the code below to reset your password:";
            default                 -> "Your verification code is:";
        };

        String body = OTP_TEMPLATE.formatted(heading, preamble, otp);
        String emailType = "OTP_" + purpose.name();

        // In local-stub mode the OTP is never emailed — log it so developers
        // can complete the verification flow without a real mail server.
        if (props.isUseLocalStub()) {
            log.info("┌─────────────────────────────────────────────┐");
            log.info("│  [DEV] OTP CODE  →  {}  ({})  │", otp, purpose);
            log.info("│  Recipient: {}",  to);
            log.info("└─────────────────────────────────────────────┘");
        }

        send(to, subject, body, emailType, companyId, userId);
    }

    /**
     * Sends a candidate interview invitation email and persists an {@link EmailEvent}.
     *
     * @param to            recipient email (lowercase)
     * @param candidateName candidate's full name for personalisation
     * @param companyName   company name for the invitation header
     * @param inviteUrl     the full invite URL (contains the invite JWT)
     * @param companyId     company that owns this session
     */
    @Transactional
    public void sendCandidateInviteEmail(String to, String candidateName, String companyName,
                                         String inviteUrl, UUID companyId) {
        String subject = "Interview invitation from " + companyName + " on InterviewIQ";
        String body = INVITE_TEMPLATE.formatted(companyName, candidateName, inviteUrl);

        send(to, subject, body, "CANDIDATE_INVITE", companyId, null);
    }


    private static final String LOW_BALANCE_TEMPLATE = """
        <html><body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
        <h2>⚠️ Low Wallet Balance Alert</h2>
        <p>Hi %s,</p>
        <p>Your InterviewIQ wallet balance has dropped to <strong>₹%d</strong>.</p>
        <p>Please top up your wallet to continue scheduling AI interviews.</p>
        <p style="margin: 30px 0;">
          <a href="%s/app/billing" style="background: #4F46E5; color: white; padding: 14px 28px;
             text-decoration: none; border-radius: 6px; font-size: 16px;">
            Top Up Wallet
          </a>
        </p>
        <p style="color: #888; font-size: 12px;">This is an automated alert from InterviewIQ.</p>
        </body></html>
        """;

    @Transactional
    public void sendLowBalanceAlert(String to, String adminName,
                                    long balancePaise, UUID companyId,
                                    String frontendBaseUrl) {
        String subject = "⚠️ Low wallet balance alert — InterviewIQ";
        long balanceRupees = balancePaise / 100;
        String body = LOW_BALANCE_TEMPLATE.formatted(
                adminName,
                balanceRupees,
                frontendBaseUrl
        );
        send(to, subject, body, "LOW_BALANCE_ALERT", companyId, null);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void send(String to, String subject, String htmlBody,
                      String emailType, UUID companyId, UUID userId) {
        String recipientLower = to.toLowerCase();
        EmailEvent event = buildEvent(recipientLower, emailType, companyId, userId);

        if (props.isUseLocalStub()) {
            log.info("[SES STUB] to={} subject={} body_chars={}", recipientLower, subject, htmlBody.length());
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            event.setCreatedAt(now);
            event.setStatus(EmailStatus.SENT);
            event.setProviderMessageId("stub-" + UUID.randomUUID());
            event.setSentAt(now);
            emailEventRepository.save(event);
            return;
        }

        try {
            SendEmailRequest request = SendEmailRequest.builder()
                    .destination(Destination.builder().toAddresses(recipientLower).build())
                    .message(Message.builder()
                            .subject(Content.builder().data(subject).charset("UTF-8").build())
                            .body(Body.builder()
                                    .html(Content.builder().data(htmlBody).charset("UTF-8").build())
                                    .build())
                            .build())
                    .source(props.getSesFromAddress())
                    .build();

            SendEmailResponse response = sesClient.sendEmail(request);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);  // ← ADD
            event.setCreatedAt(now);
            event.setStatus(EmailStatus.SENT);
            event.setProviderMessageId(response.messageId());
            event.setSentAt(now);
            log.debug("Email sent: to={} type={} messageId={}", recipientLower, emailType, response.messageId());

        } catch (SesException e) {
            // SES service error — invalid recipient, quota exceeded, etc.
            log.warn("SES service error: to={} type={} error={}", recipientLower, emailType, e.getMessage());
            event.setStatus(EmailStatus.FAILED);
        } catch (SdkException e) {
            // SDK client error — missing credentials, no network, endpoint unreachable.
            // Common in local dev when AWS is not configured and use-local-stub is false.
            // Log as WARN (not ERROR) — the request itself succeeded; only email delivery failed.
            log.warn("SES client error (check AWS credentials or set use-local-stub=true): " +
                     "to={} type={} error={}", recipientLower, emailType, e.getMessage());
            event.setStatus(EmailStatus.FAILED);
        }

        emailEventRepository.save(event);
    }

    private EmailEvent buildEvent(String recipientEmail, String emailType,
                                   UUID companyId, UUID userId) {
        EmailEvent event = new EmailEvent();
        event.setRecipientEmail(recipientEmail);
        event.setEmailType(emailType);
        event.setCompanyId(companyId);
        event.setUserId(userId);
        event.setStatus(EmailStatus.QUEUED);
        event.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return event;
    }
}
