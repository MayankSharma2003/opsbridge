package io.opsbridge.support.notification;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.repository.IncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

// Emails the team intake address when an incident is declared. Structured so nothing here can
// affect the declaration itself: AFTER_COMMIT so the incident is durable before anyone hears about
// it, @Async on a dedicated pool so the request doesn't wait on SMTP, and a catch-all so a send
// failure never surfaces to the already-answered caller.
// Re-reads the incident in its own transaction (same reason as IncidentChangedListener) -- the
// declaring transaction is closed and this runs on a different thread, so lazy associations would
// otherwise fail to load.
@Component
public class IncidentDeclaredMailListener {

    private static final Logger log = LoggerFactory.getLogger(IncidentDeclaredMailListener.class);

    private final IncidentRepository incidentRepository;
    private final MailProperties mailProperties;

    /** Null when no SMTP host is configured -- see {@link #IncidentDeclaredMailListener}. */
    @Nullable
    private final JavaMailSender mailSender;

    // Read straight from spring.mail.username, not MailProperties -- the credential belongs to the
    // transport, this class only needs to know whether one exists. Never logged.
    private final String smtpUsername;

    // ObjectProvider, not a plain JavaMailSender -- Spring Boot only auto-configures that bean when
    // spring.mail.host is set, and a hard dependency on a missing bean would fail startup.
    @Autowired
    public IncidentDeclaredMailListener(
            IncidentRepository incidentRepository,
            MailProperties mailProperties,
            ObjectProvider<JavaMailSender> mailSenderProvider,
            @Value("${spring.mail.username:}") String smtpUsername) {
        this(incidentRepository, mailProperties, mailSenderProvider.getIfAvailable(), smtpUsername);
    }

    // Takes the resolved sender directly, for tests; the constructor above is @Autowired so Spring
    // never has to choose between the two.
    IncidentDeclaredMailListener(
            IncidentRepository incidentRepository,
            MailProperties mailProperties,
            @Nullable JavaMailSender mailSender,
            String smtpUsername) {
        this.incidentRepository = incidentRepository;
        this.mailProperties = mailProperties;
        this.mailSender = mailSender;
        this.smtpUsername = smtpUsername;
        // Warn once at startup, not per incident -- an intentional no-mail deployment shouldn't get
        // its logs filled by that choice.
        String reason = misconfigurationReason();
        if (reason != null) {
            log.warn("Incident declaration emails are disabled: {}", reason);
        }
    }

    @Async(MailAsyncConfig.MAIL_EXECUTOR)
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onIncidentDeclared(IncidentDeclaredEvent event) {
        try {
            if (misconfigurationReason() != null) {
                return;
            }
            Incident incident = incidentRepository.findById(event.incidentId()).orElse(null);
            if (incident == null) {
                // Declared, then deleted before this ran. Nothing to announce.
                return;
            }
            mailSender.send(compose(incident));
            log.info("Sent declaration email for incident {}", incident.getPublicKey());
        } catch (Exception ex) {
            // Broad on purpose -- incident's committed and caller's answered, nothing here is worth
            // propagating.
            log.error("Failed to send declaration email for incident {}", event.incidentId(), ex);
        }
    }

    private SimpleMailMessage compose(Incident incident) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailProperties.from());
        message.setTo(mailProperties.recipients());
        message.setSubject("[OpsBridge] %s %s declared: %s"
                .formatted(incident.getPriority(), incident.getSeverity(), incident.getPublicKey()));
        message.setText(body(incident));
        return message;
    }

    // Plain text with raw enum values on purpose -- operator alert, not rendered UI; i18n mapping is
    // the frontend's job and there's no viewer locale here anyway.
    private String body(Incident incident) {
        return """
                A new incident has been declared.

                Incident: %s
                Title:    %s
                Service:  %s
                Priority: %s
                Severity: %s
                Status:   %s
                Reporter: %s

                Description:
                %s
                """
                .formatted(
                        incident.getPublicKey(),
                        incident.getTitle(),
                        incident.getService().getName(),
                        incident.getPriority(),
                        incident.getSeverity(),
                        incident.getStatus(),
                        incident.getReporter().getName(),
                        incident.getDescription() == null ? "(none)" : incident.getDescription());
    }

    /** Why sending is off, or null when it is fully configured and on. */
    @Nullable
    private String misconfigurationReason() {
        if (!mailProperties.enabled()) {
            return "opsbridge.mail.enabled is false";
        }
        if (mailSender == null) {
            return "no mail sender is configured (MAIL_HOST unset)";
        }
        if (smtpUsername == null || smtpUsername.isBlank()) {
            return "MAIL_USERNAME is not set";
        }
        if (mailProperties.from() == null || mailProperties.from().isBlank()) {
            return "MAIL_FROM is not set";
        }
        if (mailProperties.recipients().length == 0) {
            return "MAIL_NOTIFY_TO is not set";
        }
        return null;
    }
}
