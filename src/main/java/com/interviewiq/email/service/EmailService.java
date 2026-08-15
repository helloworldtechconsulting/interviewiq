package com.interviewiq.email.service;

import com.interviewiq.auth.domain.OtpPurpose;
import com.interviewiq.email.domain.EmailEvent;
import com.interviewiq.email.domain.EmailStatus;
import com.interviewiq.email.infrastructure.EmailEventRepository;
import com.interviewiq.shared.config.MailProperties;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
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
 * {@link MailProperties#isUseLocalStub()} is {@code true}.
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

    private final JavaMailSender         mailSender;
    private final MailProperties         mailProperties;
    private final EmailEventRepository   emailEventRepository;

    public EmailService(JavaMailSender mailSender,
                        MailProperties mailProperties,
                        EmailEventRepository emailEventRepository) {
        this.mailSender           = mailSender;
        this.mailProperties       = mailProperties;
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
        if (mailProperties.isUseLocalStub()) {
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

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Sends one email over SMTP.
     *
     * <p>Moved off the SES SDK in v2.1 (PRD §9.2, Arch v4.0 §3). SES is
     * AWS-specific, and the provider is now a configuration value — Resend,
     * Brevo, Postmark or SES-over-SMTP all work through the same
     * {@code JavaMailSender}.
     *
     * <p>The PRD is honest that this costs something: "portable SMTP providers
     * cost more than SES for the same deliverability; that is a real, accepted
     * cost of portability."
     *
     * <p>Optionally supports one attachment, which is what the booking
     * confirmation's {@code .ics} needs (§7.4.1).
     */
    private void send(String to, String subject, String htmlBody,
                      String emailType, UUID companyId, UUID userId,
                      Attachment attachment) {
        String recipientLower = to.toLowerCase();
        EmailEvent event = buildEvent(recipientLower, emailType, companyId, userId);

        if (mailProperties.isUseLocalStub()) {
            log.info("[SMTP STUB] to={} subject={} body_chars={} attachment={}",
                    recipientLower, subject, htmlBody.length(),
                    attachment == null ? "none" : attachment.filename());
            event.setStatus(EmailStatus.SENT);
            event.setProviderMessageId("stub-" + UUID.randomUUID());
            event.setSentAt(OffsetDateTime.now(ZoneOffset.UTC));
            emailEventRepository.save(event);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            // multipart only when there is actually an attachment — a needless
            // multipart wrapper hurts deliverability with some filters.
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, attachment != null, StandardCharsets.UTF_8.name());

            helper.setTo(recipientLower);
            helper.setFrom(mailProperties.getFromAddress(), mailProperties.getFromName());
            helper.setSubject(subject);
            helper.setText(htmlBody, true);

            if (attachment != null) {
                helper.addAttachment(attachment.filename(),
                        new ByteArrayResource(attachment.content()), attachment.contentType());
            }

            mailSender.send(message);

            event.setStatus(EmailStatus.SENT);
            event.setProviderMessageId(message.getMessageID());
            event.setSentAt(OffsetDateTime.now(ZoneOffset.UTC));
            log.debug("Email sent: to={} type={}", recipientLower, emailType);

        } catch (Exception e) {
            // Never rethrown. A failed OTP email is recoverable — the user can
            // request another — while an exception here would fail their
            // registration outright. The FAILED row is what surfaces the problem.
            log.warn("SMTP send failed: to={} type={} error={}", recipientLower, emailType, e.getMessage());
            event.setStatus(EmailStatus.FAILED);
        }

        emailEventRepository.save(event);
    }

    private void send(String to, String subject, String htmlBody,
                      String emailType, UUID companyId, UUID userId) {
        send(to, subject, htmlBody, emailType, companyId, userId, null);
    }

    /** One email attachment — in practice the booking confirmation's .ics file. */
    public record Attachment(String filename, String contentType, byte[] content) {}

    private EmailEvent buildEvent(String recipientEmail, String emailType,
                                   UUID companyId, UUID userId) {
        EmailEvent event = new EmailEvent();
        event.setRecipientEmail(recipientEmail);
        event.setEmailType(emailType);
        event.setCompanyId(companyId);
        event.setUserId(userId);
        event.setStatus(EmailStatus.QUEUED);
        return event;
    }
}
