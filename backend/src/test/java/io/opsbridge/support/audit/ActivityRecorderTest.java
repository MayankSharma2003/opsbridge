package io.opsbridge.support.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.entity.IncidentActivity;
import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.ActivityEntityType;
import io.opsbridge.domain.enums.ActivityEventType;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.repository.IncidentActivityRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ActivityRecorderTest {

    private static final Instant NOW = Instant.parse("2026-03-01T09:00:00Z");

    private IncidentActivityRepository repository;
    private ActivityRecorder recorder;

    private User actor;
    private Service service;
    private Incident incident;

    @BeforeEach
    void setUp() {
        repository = mock(IncidentActivityRepository.class);
        when(repository.save(any(IncidentActivity.class))).thenAnswer(call -> call.getArgument(0));
        recorder = new ActivityRecorder(repository, Clock.fixed(NOW, ZoneOffset.UTC));

        actor = user("operator@opsbridge.local");
        service = service();
        incident = new Incident("INC-1000", "Checkout is down", null, service, actor, Priority.P1, Severity.SEV1);
        ReflectionTestUtils.setField(incident, "id", UUID.randomUUID());
    }

    @Test
    void record_writesAnIncidentTargetedRowStampedFromTheClock() {
        IncidentActivity row = recorder.record(
                incident,
                actor,
                ActivityEventType.STATUS_CHANGED,
                IncidentStatus.OPEN.name(),
                IncidentStatus.TRIAGED.name(),
                "picked up");

        assertThat(row.getEntityType()).isEqualTo(ActivityEntityType.INCIDENT);
        assertThat(row.getIncident()).isEqualTo(incident);
        assertThat(row.getService()).isNull();
        assertThat(row.getActor()).isEqualTo(actor);
        assertThat(row.getFieldName()).isNull();
        assertThat(row.getFromValue()).isEqualTo("OPEN");
        assertThat(row.getToValue()).isEqualTo("TRIAGED");
        assertThat(row.getComment()).isEqualTo("picked up");
        // From the injected Clock, not Instant.now(), so audit time agrees with SLA math.
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void recordFieldChange_namesTheAttributeThatMoved() {
        IncidentActivity row = recorder.recordFieldChange(
                incident, actor, ActivityEventType.TITLE_CHANGED, "title", "Old", "New", null);

        assertThat(row.getFieldName()).isEqualTo("title");
        assertThat(row.getEntityType()).isEqualTo(ActivityEntityType.INCIDENT);
    }

    @Test
    void recordServiceFieldChange_writesAServiceTargetedRowWithNoIncident() {
        IncidentActivity row = recorder.recordServiceFieldChange(
                service, actor, ActivityEventType.SERVICE_UPDATED, "team", "Payments", "Growth", null);

        assertThat(row.getEntityType()).isEqualTo(ActivityEntityType.SERVICE);
        assertThat(row.getService()).isEqualTo(service);
        // Must stay null -- the V5 CHECK constraint rejects a row claiming both targets.
        assertThat(row.getIncident()).isNull();
        assertThat(row.getFieldName()).isEqualTo("team");
        assertThat(row.getFromValue()).isEqualTo("Payments");
        assertThat(row.getToValue()).isEqualTo("Growth");
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
    }

    private static User user(String email) {
        User user = new User("Test " + email, email, "hash", Role.OPERATOR);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private static Service service() {
        Service service = new Service("Checkout API", null, null);
        ReflectionTestUtils.setField(service, "id", UUID.randomUUID());
        return service;
    }
}
