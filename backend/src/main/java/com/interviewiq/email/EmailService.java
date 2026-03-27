package com.interviewiq.email;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${app.email.from}")
    private String fromEmail;

    @Value("${app.email.sender-name}")
    private String senderName;

    @Value("${app.email.base-url}")
    private String baseUrl;

    public void sendInterviewInvite(String candidateEmail, String candidateName, String jobTitle, String inviteToken) {
        try {
            String inviteUrl = baseUrl + "/interview/" + inviteToken;

            String subject = "Interview Invitation - " + jobTitle;
            String body = String.format(
                    "Dear %s,\n\n" +
                    "You have been invited to an interview for the position of %s.\n\n" +
                    "Please click the link below to complete your interview:\n" +
                    "%s\n\n" +
                    "The link will expire in 72 hours.\n\n" +
                    "Best regards,\n%s",
                    candidateName, jobTitle, inviteUrl, senderName
            );

            sendEmail(candidateEmail, subject, body);
            log.info("Interview invitation email sent to: {}", candidateEmail);
        } catch (Exception e) {
            log.error("Failed to send interview invitation email to: {}", candidateEmail, e);
        }
    }

    public void sendInterviewCompleted(String candidateEmail, String candidateName, String jobTitle) {
        try {
            String subject = "Interview Completed - " + jobTitle;
            String body = String.format(
                    "Dear %s,\n\n" +
                    "Thank you for completing your interview for the position of %s.\n\n" +
                    "We will review your interview and get back to you soon.\n\n" +
                    "Best regards,\n%s",
                    candidateName, jobTitle, senderName
            );

            sendEmail(candidateEmail, subject, body);
            log.info("Interview completion email sent to: {}", candidateEmail);
        } catch (Exception e) {
            log.error("Failed to send interview completion email to: {}", candidateEmail, e);
        }
    }

    public void sendPaymentReceipt(String email, String companyName, long amountPaise, String transactionId) {
        try {
            double amountRupees = amountPaise / 100.0;
            String subject = "Payment Receipt - " + companyName;
            String body = String.format(
                    "Dear %s,\n\n" +
                    "Your payment of ₹%.2f has been received successfully.\n\n" +
                    "Transaction ID: %s\n\n" +
                    "Thank you for using InterviewIQ.\n\n" +
                    "Best regards,\n%s",
                    companyName, amountRupees, transactionId, senderName
            );

            sendEmail(email, subject, body);
            log.info("Payment receipt email sent to: {}", email);
        } catch (Exception e) {
            log.error("Failed to send payment receipt email to: {}", email, e);
        }
    }

    private void sendEmail(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        mailSender.send(message);
    }
}
