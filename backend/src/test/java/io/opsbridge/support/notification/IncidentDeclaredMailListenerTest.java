package io.opsbridge.support.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.repository.IncidentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

// Send when configured, and never let a mail problem escape -- by the time this runs the
// incident is committed and the caller already answered, so a thrown exception has nowhere to go.
class IncidentDeclaredMailListenerTest {

    private static final String USERNAME = "smtp-user";

    private IncidentRepository incidentRepository;
    private JavaMailSender mailSender;
    private Incident incident;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        mailSender = mock(JavaMailSender.class);

        User reporter = user("reporter@opsbridge.local");
        Service service = service("Checkout API");
        incident = new Incident(
                "INC-1000", "Checkout latency spike", "p99 over 4s", service, reporter, Priority.P1, Severity.SEV2);
        ReflectionTestUtils.setField(incident, "id", UUID.randomUUID());
        when(incidentRepository.findById(incident.getId())).thenReturn(Optional.of(incident));
    }

    @Test
    void sendsADeclarationEmailToTheConfiguredTeamAddress() {
        listener(enabled()).onIncidentDeclared(new IncidentDeclaredEvent(incident.getId()));

        SimpleMailMessage sent = captureSent();
        assertThat(sent.getFrom()).isEqualTo("opsbridge@example.com");
        assertThat(sent.getTo()).containsExactly("incidents@example.com");
        assertThat(sent.getSubject()).isEqualTo("[OpsBridge] P1 SEV2 declared: INC-1000");
        assertThat(sent.getText())
                .contains("INC-1000")
                .contains("Checkout latency spike")
                .contains("Checkout API")
                .contains("Test reporter@opsbridge.local")
                .contains("p99 over 4s");
    }

    @Test
    void splitsACommaSeparatedRecipientList() {
        listener(new MailProperties(true, "opsbridge@example.com", "a@example.com, b@example.com"))
                .onIncidentDeclared(new IncidentDeclaredEvent(incident.getId()));

        assertThat(captureSent().getTo()).containsExactly("a@example.com", "b@example.com");
    }

    @Test
    void swallowsASendFailureInsteadOfPropagatingIt() {
        doThrow(new MailSendException("smtp refused the connection"))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        assertThatCode(() -> listener(enabled()).onIncidentDeclared(new IncidentDeclaredEvent(incident.getId())))
                .doesNotThrowAnyException();
    }

    @Test
    void swallowsAnUnexpectedRuntimeFailureToo() {
        // Not every failure is a MailException -- a transport bug must be contained just the same.
        doThrow(new IllegalStateException("transport exploded"))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        assertThatCode(() -> listener(enabled()).onIncidentDeclared(new IncidentDeclaredEvent(incident.getId())))
                .doesNotThrowAnyException();
    }

    @Test
    void sendsNothingWhenTheIncidentVanishedBeforeTheAsyncSendRan() {
        UUID unknown = UUID.randomUUID();
        when(incidentRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatCode(() -> listener(enabled()).onIncidentDeclared(new IncidentDeclaredEvent(unknown)))
                .doesNotThrowAnyException();

        verifyNoInteractions(mailSender);
    }

    @Test
    void sendsNothingWhenNoMailSenderIsConfigured() {
        IncidentDeclaredMailListener listener =
                new IncidentDeclaredMailListener(incidentRepository, enabled(), (JavaMailSender) null, USERNAME);

        assertThatCode(() -> listener.onIncidentDeclared(new IncidentDeclaredEvent(incident.getId())))
                .doesNotThrowAnyException();

        verifyNoInteractions(incidentRepository);
    }

    @Test
    void sendsNothingWhenSmtpCredentialsAreMissing() {
        IncidentDeclaredMailListener listener =
                new IncidentDeclaredMailListener(incidentRepository, enabled(), mailSender, "");

        listener.onIncidentDeclared(new IncidentDeclaredEvent(incident.getId()));

        verifyNoInteractions(mailSender);
    }

    @Test
    void sendsNothingWhenNoRecipientIsConfigured() {
        listener(new MailProperties(true, "opsbridge@example.com", ""))
                .onIncidentDeclared(new IncidentDeclaredEvent(incident.getId()));

        verifyNoInteractions(mailSender);
    }

    @Test
    void sendsNothingWhenDisabled() {
        // The switch the test profile relies on, so a regression can't let the suite hit real SMTP.
        listener(new MailProperties(false, "opsbridge@example.com", "incidents@example.com"))
                .onIncidentDeclared(new IncidentDeclaredEvent(incident.getId()));

        verifyNoInteractions(mailSender);
    }

    private IncidentDeclaredMailListener listener(MailProperties properties) {
        return new IncidentDeclaredMailListener(incidentRepository, properties, mailSender, USERNAME);
    }

    private static MailProperties enabled() {
        return new MailProperties(true, "opsbridge@example.com", "incidents@example.com");
    }

    private SimpleMailMessage captureSent() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    private static User user(String email) {
        User user = new User("Test " + email, email, "hash", Role.OPERATOR);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private static Service service(String name) {
        Service service = new Service(name, null, null);
        ReflectionTestUtils.setField(service, "id", UUID.randomUUID());
        return service;
    }
}
