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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    /**
     * Shared shell for the scheduling and notification emails.
     *
     * <p>Deliberately plain HTML with inline styles and no images. Every one of
     * these lands in a candidate's personal inbox or a recruiter's work inbox on
     * first contact, and image-heavy templates from an unfamiliar sender are what
     * spam filters are tuned to catch. Deliverability is the feature here, not
     * design — a reminder that lands in spam is a no-show.
     */
    private static final String SHELL = """
            <html><body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto; color: #222;">
            <h2 style="font-size: 20px;">%s</h2>
            %s
            <p style="color: #888; font-size: 12px; margin-top: 32px;">%s</p>
            </body></html>
            """;

    private static final String CTA = """
            <p style="margin: 28px 0;">
              <a href="%s" style="background: #4F46E5; color: white; padding: 14px 28px;
                 text-decoration: none; border-radius: 6px; font-size: 16px; display: inline-block;">%s</a>
            </p>
            """;

    /**
     * Every candidate-facing time is rendered with an explicit zone.
     *
     * <p>§7.4.6 requires this and it is the single most consequential formatting
     * decision in the product: a candidate who reads "15:00" and assumes their own
     * timezone misses their interview, and we record that as a no-show against
     * them. The offset is always shown, never inferred.
     */
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' HH:mm (zzz)");

    private final JavaMailSender         mailSender;
    private final MailProperties         mailProperties;
    private final EmailEventRepository   emailEventRepository;
    private final EmailSuppressionService suppressionService;

    public EmailService(JavaMailSender mailSender,
                        MailProperties mailProperties,
                        EmailEventRepository emailEventRepository,
                        EmailSuppressionService suppressionService) {
        this.mailSender           = mailSender;
        this.mailProperties       = mailProperties;
        this.emailEventRepository = emailEventRepository;
        this.suppressionService   = suppressionService;
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
    // Scheduling emails (§7.4, INTIQ-92)
    // =========================================================================

    /**
     * Confirms a booked slot and attaches the {@code .ics}.
     *
     * <p>The attachment is the whole reason {@link #send} grew attachment
     * support: a one-click calendar entry is what stops a booking from being
     * something the candidate has to remember unaided.
     */
    @Transactional
    public void sendBookingConfirmationEmail(String to, String candidateName, String companyName,
                                             OffsetDateTime startAt, ZoneId candidateZone,
                                             String joinUrl, byte[] icsContent, UUID companyId) {
        String when = formatWhen(startAt, candidateZone);
        String body = SHELL.formatted(
                "Your interview with " + companyName + " is confirmed",
                "<p>Hi " + escape(candidateName) + ",</p>"
                        + "<p>Your interview is booked for <strong>" + when + "</strong>.</p>"
                        + "<p>A calendar invitation is attached. The interview runs in your browser — "
                        + "please use Chrome, Edge, Brave or Arc, and allow camera and microphone access "
                        + "when prompted.</p>"
                        + CTA.formatted(joinUrl, "Join at the scheduled time"),
                "Need a different time? You can reschedule once, up to an hour before your slot.");

        send(to, "Interview confirmed — " + when, body, "BOOKING_CONFIRMATION", companyId, null,
                new Attachment("interview.ics", "text/calendar; charset=UTF-8", icsContent));
    }

    /** Reschedule confirmation — carries the updated {@code .ics}, which supersedes the original by UID. */
    @Transactional
    public void sendRescheduleConfirmationEmail(String to, String candidateName, String companyName,
                                                OffsetDateTime startAt, ZoneId candidateZone,
                                                String joinUrl, byte[] icsContent, UUID companyId) {
        String when = formatWhen(startAt, candidateZone);
        String body = SHELL.formatted(
                "Your interview has been moved",
                "<p>Hi " + escape(candidateName) + ",</p>"
                        + "<p>Your interview with " + escape(companyName) + " is now on <strong>"
                        + when + "</strong>.</p>"
                        + "<p>The attached calendar invitation replaces the previous one.</p>"
                        + CTA.formatted(joinUrl, "Join at the new time"),
                "This was your one reschedule — the new time is final.");

        send(to, "Interview moved to " + when, body, "RESCHEDULE_CONFIRMATION", companyId, null,
                new Attachment("interview.ics", "text/calendar; charset=UTF-8", icsContent));
    }

    /**
     * Interview reminder at T-24h or T-1h.
     *
     * <p>The T-1h reminder re-attaches the {@code .ics}; the T-24h one does not.
     * At an hour out the candidate is looking for the link, not the calendar
     * entry, and a second identical attachment a day later is noise.
     */
    @Transactional
    public void sendInterviewReminderEmail(String to, String candidateName, String companyName,
                                           OffsetDateTime startAt, ZoneId candidateZone,
                                           String joinUrl, boolean oneHourOut,
                                           byte[] icsContent, UUID companyId) {
        String when = formatWhen(startAt, candidateZone);
        String lead = oneHourOut
                ? "<p>Your interview with " + escape(companyName) + " starts in about an hour, at <strong>"
                  + when + "</strong>.</p>"
                  + "<p>Find a quiet place with a stable connection. The room opens five minutes early.</p>"
                : "<p>Your interview with " + escape(companyName) + " is tomorrow, at <strong>"
                  + when + "</strong>.</p>";

        String body = SHELL.formatted(
                oneHourOut ? "Your interview starts in an hour" : "Your interview is tomorrow",
                "<p>Hi " + escape(candidateName) + ",</p>" + lead
                        + CTA.formatted(joinUrl, "Open the interview room"),
                "Chrome, Edge, Brave or Arc, with camera and microphone access.");

        Attachment ics = oneHourOut && icsContent != null
                ? new Attachment("interview.ics", "text/calendar; charset=UTF-8", icsContent)
                : null;

        send(to, (oneHourOut ? "Starting soon: " : "Tomorrow: ") + "your interview with " + companyName,
                body, oneHourOut ? "REMINDER_1H" : "REMINDER_24H", companyId, null, ics);
    }

    /** Tells the recruiter a candidate did not attend, and that they were not charged. */
    @Transactional
    public void sendNoShowNoticeEmail(String to, String candidateName, String jobTitle,
                                      OffsetDateTime scheduledAt, UUID companyId) {
        String body = SHELL.formatted(
                "Candidate did not attend",
                "<p><strong>" + escape(candidateName) + "</strong> did not join their interview for "
                        + escape(jobTitle) + ", scheduled for "
                        + formatWhen(scheduledAt, ZoneOffset.UTC) + ".</p>"
                        + "<p>You have not been charged — the reserved ₹100 has been returned to your "
                        + "wallet. You can send them a new invitation from the candidate's page.</p>",
                "InterviewIQ");

        send(to, "No-show: " + candidateName, body, "NO_SHOW_NOTICE", companyId, null);
    }

    // =========================================================================
    // Recruiter notifications (§7.7, INTIQ-71)
    // =========================================================================

    /** Tells the recruiter a report is ready to read. */
    @Transactional
    public void sendReportReadyEmail(String to, String candidateName, String jobTitle,
                                     int overallScore, String reportUrl, UUID companyId) {
        String body = SHELL.formatted(
                "Interview report ready",
                "<p><strong>" + escape(candidateName) + "</strong> has completed their interview for "
                        + escape(jobTitle) + ".</p>"
                        + "<p>Overall score: <strong>" + overallScore + "/100</strong></p>"
                        + CTA.formatted(reportUrl, "Read the full report"),
                "This score is advisory only. InterviewIQ does not advance or reject any candidate — "
                        + "a human makes every hiring decision.");

        send(to, candidateName + "'s interview report is ready", body, "REPORT_READY", companyId, null);
    }

    /**
     * Warns that the wallet is running low.
     *
     * <p>States the remaining interview count rather than only the balance.
     * "₹250 remaining" requires the reader to know the per-interview price;
     * "enough for 2 more interviews" is the number they actually act on.
     */
    @Transactional
    public void sendLowBalanceEmail(String to, String companyName, long balancePaise,
                                    long interviewsRemaining, String topUpUrl, UUID companyId) {
        String body = SHELL.formatted(
                "Your InterviewIQ balance is running low",
                "<p>" + escape(companyName) + "'s wallet balance is <strong>₹"
                        + (balancePaise / 100) + "</strong> — enough for "
                        + interviewsRemaining + (interviewsRemaining == 1 ? " more interview" : " more interviews")
                        + ".</p>"
                        + "<p>Interviews cannot be sent once the balance will not cover them, so topping up "
                        + "now avoids a hiring drive stalling midway.</p>"
                        + CTA.formatted(topUpUrl, "Top up your wallet"),
                "You will not receive this again until after your next top-up.");

        send(to, "Low balance — top up to keep interviewing", body, "LOW_BALANCE", companyId, null);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /** Renders an instant in the recipient's own zone, always with the zone shown. */
    private String formatWhen(OffsetDateTime at, ZoneId zone) {
        ZoneId target = zone == null ? ZoneOffset.UTC : zone;
        return WHEN.format(at.atZoneSameInstant(target));
    }

    /**
     * Escapes the handful of characters that would otherwise let a name or job
     * title break out of the surrounding HTML.
     *
     * <p>These values come from user input — a candidate's name arrives from a
     * CSV import, and a job title is typed by a recruiter. Neither is trusted
     * markup, and an email body is a rendering context like any other.
     */
    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;");
    }

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

        // Suppression is checked before anything else, including the local stub
        // — the stub exists to mirror real behaviour, and a stub that "sends" to
        // a suppressed address would hide the very bug this guard prevents.
        //
        // The check is unconditional across email types. It is tempting to
        // exempt OTPs so a user can always get back in, but an address on this
        // list hard-bounced or reported us as spam: the OTP would not arrive
        // either way, and sending it anyway only compounds the reputation
        // damage that got the address suppressed. Recovery is releasing the
        // address (EmailSuppressionService.release), not routing around it.
        if (suppressionService.isSuppressed(recipientLower)) {
            log.info("Send withheld — address is suppressed: to={} type={}", recipientLower, emailType);
            event.setStatus(EmailStatus.SUPPRESSED);
            emailEventRepository.save(event);
            return;
        }

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
