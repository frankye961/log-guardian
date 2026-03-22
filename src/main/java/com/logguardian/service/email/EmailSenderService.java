package com.logguardian.service.email;

import com.logguardian.ai.model.IncidentSeverity;
import com.logguardian.ai.model.IncidentSummary;
import com.logguardian.fingerprint.anomaly.AnomalyEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class EmailSenderService {

    private final JavaMailSender mailSender;
    private final boolean enabled;
    private final String from;
    private final String replyTo;
    private final String recipientList;
    private final String subjectPrefix;

    public EmailSenderService(
            JavaMailSender mailSender,
            @Value("${logguardian.notifications.email.enabled:true}") boolean enabled,
            @Value("${logguardian.notifications.email.from:}") String from,
            @Value("${logguardian.notifications.email.reply-to:}") String replyTo,
            @Value("${logguardian.notifications.email.to:}") String recipientList,
            @Value("${logguardian.notifications.email.subject-prefix:[LogGuardian]}") String subjectPrefix
    ) {
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.from = from;
        this.replyTo = replyTo;
        this.recipientList = recipientList;
        this.subjectPrefix = subjectPrefix;
    }

    public void sendIncidentEmail(AnomalyEvent anomaly, IncidentSummary summary) {
        if (!enabled) {
            log.debug("Email notification skipped because it is disabled");
            return;
        }

        List<String> recipients = Arrays.stream(recipientList.split(","))
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();

        if (recipients.isEmpty()) {
            log.warn("Email notification skipped because no recipients are configured");
            return;
        }

        String subject = buildSubject(anomaly, summary);
        String plainTextBody = buildPlainTextBody(anomaly, summary);
        String htmlBody = buildHtmlBody(anomaly, summary);

        try {
            var message = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

            helper.setTo(recipients.toArray(String[]::new));
            if (StringUtils.hasText(from)) {
                helper.setFrom(from.trim());
            }
            if (StringUtils.hasText(replyTo)) {
                helper.setReplyTo(replyTo.trim());
            }
            helper.setSubject(subject);
            helper.setText(plainTextBody, htmlBody);

            mailSender.send(message);
            log.info("Incident email sent for fingerprint={} to {}", anomaly.fingerprint(), recipients);
        } catch (MailException | jakarta.mail.MessagingException exception) {
            log.error("Failed to send incident email for fingerprint={}", anomaly.fingerprint(), exception);
        }
    }

    private String buildSubject(AnomalyEvent anomaly, IncidentSummary summary) {
        String severity = summary.severity() == null ? IncidentSeverity.UNKNOWN.name() : summary.severity().name();
        String sourceName = safe(anomaly.sourceName());
        return "%s [%s] %s anomaly in %s".formatted(
                safeSubjectPrefix(),
                severity,
                anomaly.level(),
                sourceName
        );
    }

    private String buildPlainTextBody(AnomalyEvent anomaly, IncidentSummary summary) {
        return """
                LogGuardian detected an anomaly and generated the following incident summary.

                Incident overview
                - Title: %s
                - Severity: %s
                - Log level: %s
                - Occurrences in the current window: %d
                - Detected at (UTC): %s
                - Source name: %s
                - Source id: %s
                - Fingerprint: %s

                What happened
                %s

                Probable cause
                %s

                Recommended investigation steps
                %s

                Sample log
                %s

                This email was generated automatically by LogGuardian.
                """.formatted(
                safe(summary.title()),
                renderSeverity(summary),
                anomaly.level(),
                anomaly.count(),
                formatTimestamp(anomaly),
                safe(anomaly.sourceName()),
                safe(anomaly.sourceId()),
                safe(anomaly.fingerprint()),
                safe(summary.summary()),
                safe(summary.probableCause()),
                safe(summary.suggestedActions()),
                safe(anomaly.sampleMessage())
        );
    }

    private String buildHtmlBody(AnomalyEvent anomaly, IncidentSummary summary) {
        String severity = renderSeverity(summary);
        return """
                <html>
                  <body style="margin:0;padding:24px;background:#f4f7fb;font-family:Arial,sans-serif;color:#172033;">
                    <div style="max-width:760px;margin:0 auto;background:#ffffff;border:1px solid #d9e2f0;border-radius:16px;overflow:hidden;">
                      <div style="padding:24px 28px;background:linear-gradient(135deg,#172033,#25426f);color:#ffffff;">
                        <div style="font-size:12px;letter-spacing:0.12em;text-transform:uppercase;opacity:0.8;">LogGuardian Incident Notification</div>
                        <h1 style="margin:10px 0 0;font-size:28px;line-height:1.2;">%s</h1>
                        <p style="margin:12px 0 0;font-size:15px;line-height:1.6;opacity:0.92;">An anomaly was detected in <strong>%s</strong> and has been summarized below for rapid triage.</p>
                      </div>
                      <div style="padding:28px;">
                        <table style="width:100%%;border-collapse:collapse;margin-bottom:24px;">
                          <tr>
                            <td style="padding:0 0 16px;width:50%%;vertical-align:top;">
                              <div style="font-size:12px;text-transform:uppercase;color:#60708b;letter-spacing:0.08em;">Severity</div>
                              <div style="font-size:18px;font-weight:700;color:#172033;">%s</div>
                            </td>
                            <td style="padding:0 0 16px;width:50%%;vertical-align:top;">
                              <div style="font-size:12px;text-transform:uppercase;color:#60708b;letter-spacing:0.08em;">Log Level</div>
                              <div style="font-size:18px;font-weight:700;color:#172033;">%s</div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:0 0 16px;vertical-align:top;">
                              <div style="font-size:12px;text-transform:uppercase;color:#60708b;letter-spacing:0.08em;">Occurrences</div>
                              <div style="font-size:18px;font-weight:700;color:#172033;">%d in current window</div>
                            </td>
                            <td style="padding:0 0 16px;vertical-align:top;">
                              <div style="font-size:12px;text-transform:uppercase;color:#60708b;letter-spacing:0.08em;">Detected At</div>
                              <div style="font-size:18px;font-weight:700;color:#172033;">%s</div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:0 0 16px;vertical-align:top;">
                              <div style="font-size:12px;text-transform:uppercase;color:#60708b;letter-spacing:0.08em;">Source</div>
                              <div style="font-size:18px;font-weight:700;color:#172033;">%s</div>
                            </td>
                            <td style="padding:0 0 16px;vertical-align:top;">
                              <div style="font-size:12px;text-transform:uppercase;color:#60708b;letter-spacing:0.08em;">Fingerprint</div>
                              <div style="font-size:14px;font-weight:700;color:#172033;word-break:break-word;">%s</div>
                            </td>
                          </tr>
                        </table>

                        <div style="margin-bottom:22px;padding:20px;background:#f7f9fc;border:1px solid #e2e8f3;border-radius:12px;">
                          <div style="font-size:12px;text-transform:uppercase;color:#60708b;letter-spacing:0.08em;margin-bottom:8px;">What Happened</div>
                          <div style="font-size:15px;line-height:1.7;color:#172033;">%s</div>
                        </div>

                        <div style="margin-bottom:22px;padding:20px;background:#f7f9fc;border:1px solid #e2e8f3;border-radius:12px;">
                          <div style="font-size:12px;text-transform:uppercase;color:#60708b;letter-spacing:0.08em;margin-bottom:8px;">Probable Cause</div>
                          <div style="font-size:15px;line-height:1.7;color:#172033;">%s</div>
                        </div>

                        <div style="margin-bottom:22px;padding:20px;background:#f7f9fc;border:1px solid #e2e8f3;border-radius:12px;">
                          <div style="font-size:12px;text-transform:uppercase;color:#60708b;letter-spacing:0.08em;margin-bottom:8px;">Recommended Investigation Steps</div>
                          <div style="font-size:15px;line-height:1.7;color:#172033;">%s</div>
                        </div>

                        <div style="padding:20px;background:#101826;border-radius:12px;">
                          <div style="font-size:12px;text-transform:uppercase;color:#9fb1d1;letter-spacing:0.08em;margin-bottom:10px;">Sample Log</div>
                          <pre style="margin:0;white-space:pre-wrap;word-break:break-word;font-size:13px;line-height:1.6;color:#f4f7fb;font-family:'SFMono-Regular',Consolas,'Liberation Mono',Menlo,monospace;">%s</pre>
                        </div>
                      </div>
                    </div>
                  </body>
                </html>
                """.formatted(
                escapeHtml(safe(summary.title())),
                escapeHtml(safe(anomaly.sourceName())),
                escapeHtml(severity),
                escapeHtml(String.valueOf(anomaly.level())),
                anomaly.count(),
                escapeHtml(formatTimestamp(anomaly)),
                escapeHtml("%s (%s)".formatted(safe(anomaly.sourceName()), safe(anomaly.sourceId()))),
                escapeHtml(safe(anomaly.fingerprint())),
                toHtmlParagraphs(summary.summary()),
                toHtmlParagraphs(summary.probableCause()),
                toHtmlParagraphs(summary.suggestedActions()),
                escapeHtml(safe(anomaly.sampleMessage()))
        );
    }

    private String safeSubjectPrefix() {
        return StringUtils.hasText(subjectPrefix) ? subjectPrefix.trim() : "[LogGuardian]";
    }

    private String renderSeverity(IncidentSummary summary) {
        return summary.severity() == null ? IncidentSeverity.UNKNOWN.name() : summary.severity().name();
    }

    private String formatTimestamp(AnomalyEvent anomaly) {
        return anomaly.detectedAt() == null
                ? "unknown"
                : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(anomaly.detectedAt().atOffset(ZoneOffset.UTC));
    }

    private String toHtmlParagraphs(String value) {
        return escapeHtml(safe(value)).replace("\n", "<br/>");
    }

    private String safe(String value) {
        return StringUtils.hasText(value) ? value.trim() : "unknown";
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
