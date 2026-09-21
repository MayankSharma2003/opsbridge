package io.opsbridge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.domain.enums.SlaState;
import io.opsbridge.repository.IncidentRepository;
import io.opsbridge.service.mapper.IncidentMapper;
import io.opsbridge.service.mapper.ServiceMapper;
import io.opsbridge.service.mapper.UserMapper;
import io.opsbridge.support.realtime.LiveEvent;
import io.opsbridge.support.realtime.LiveEventBroadcaster;
import io.opsbridge.support.realtime.LiveEventType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

// A breach is the one state change nobody writes, so this sweep is what notices it -- it must
// announce the moment an incident crosses into BREACHED exactly once, and agree with what a read would say.
class SlaBreachMonitorTest {

    private static final Instant NOW = Instant.parse("2026-03-01T09:00:00Z");

    private IncidentRepository incidentRepository;
    private LiveEventBroadcaster broadcaster;
    private MutableClock clock;
    private SlaBreachMonitor monitor;

    private Service service;
    private User reporter;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        broadcaster = mock(LiveEventBroadcaster.class);
        clock = new MutableClock(NOW);
        monitor = new SlaBreachMonitor(
                incidentRepository,
                new SlaCalculator(),
                new IncidentMapper(new ServiceMapper(new UserMapper()), new UserMapper()),
                broadcaster,
                clock);

        service = service("Checkout API");
        reporter = user("reporter@opsbridge.local");
    }

    @Test
    void announcesAnIncidentThatHasJustCrossedItsResolutionTarget() {
        Incident incident = openIncident(NOW.minus(Duration.ofMinutes(31)));
        stubCandidates(incident);

        monitor.sweep();

        ArgumentCaptor<LiveEvent> captor = ArgumentCaptor.forClass(LiveEvent.class);
        verify(broadcaster).broadcast(captor.capture());
        LiveEvent event = captor.getValue();
        assertThat(event.type()).isEqualTo(LiveEventType.SLA_BREACHED);
        assertThat(event.incident().id()).isEqualTo(incident.getId());
        // The payload must agree with a live GET, not the stale persisted column.
        assertThat(event.incident().slaState()).isEqualTo(SlaState.BREACHED);
    }

    @Test
    void announcesEachBreachOnlyOnceAcrossRepeatedSweeps() {
        Incident incident = openIncident(NOW.minus(Duration.ofMinutes(31)));
        stubCandidates(incident);

        monitor.sweep();
        clock.advance(Duration.ofMinutes(1));
        monitor.sweep();
        clock.advance(Duration.ofMinutes(1));
        monitor.sweep();

        verify(broadcaster, times(1)).broadcast(any());
    }

    @Test
    void treatsAnIncidentSittingExactlyOnItsTargetAsStillOnTime() {
        // A stage achieved exactly at its target is on time, not late.
        Incident incident = openIncident(NOW.minus(Duration.ofMinutes(30)));
        stubCandidates(incident);

        monitor.sweep();

        verifyNoInteractions(broadcaster);
    }

    @Test
    void staysQuietForAnIncidentComfortablyWithinItsTargets() {
        Incident incident = openIncident(NOW);
        stubCandidates(incident);

        monitor.sweep();

        verifyNoInteractions(broadcaster);
    }

    @Test
    void announcesNothingUntilTheDeadlineActuallyPasses() {
        // Target is 30 minutes out; nothing fires before then.
        Incident incident = openIncident(NOW);
        stubCandidates(incident);

        monitor.sweep();
        verify(broadcaster, never()).broadcast(any());

        clock.advance(Duration.ofMinutes(31));
        monitor.sweep();

        verify(broadcaster, times(1)).broadcast(any());
    }

    @Test
    void ignoresCancelledIncidentsWhoseClockHasStopped() {
        Incident incident = openIncident(NOW.minus(Duration.ofHours(5)));
        incident.setStatus(IncidentStatus.CANCELLED);
        stubCandidates(incident);

        monitor.sweep();

        verifyNoInteractions(broadcaster);
    }

    @Test
    void allowsAnIncidentToAnnounceAgainIfItLeavesAndReentersBreach() {
        Incident incident = openIncident(NOW.minus(Duration.ofMinutes(31)));
        stubCandidates(incident);
        monitor.sweep();
        verify(broadcaster, times(1)).broadcast(any());

        // Pushed out far enough to clear the breach, then back into it.
        incident.setSlaResolutionTargetAt(NOW.plus(Duration.ofHours(4)));
        monitor.sweep();
        clock.advance(Duration.ofHours(5));
        monitor.sweep();

        verify(broadcaster, times(2)).broadcast(any());
    }

    @Test
    void neverWritesToTheDatabase() {
        Incident incident = openIncident(NOW.minus(Duration.ofMinutes(31)));
        stubCandidates(incident);

        monitor.sweep();

        // Persisting would bump @Version and hand 409s to anyone with the incident open.
        verify(incidentRepository, never()).save(any());
        verify(incidentRepository, never()).saveAndFlush(any());
    }

    private void stubCandidates(Incident... incidents) {
        when(incidentRepository.findSlaBreachEvaluationCandidates(any(), anyCollection()))
                .thenReturn(List.of(incidents));
    }

    /** An open incident whose resolution target is 30 minutes after the given start. */
    private Incident openIncident(Instant startedAt) {
        Incident incident = new Incident(
                "INC-1000", "Checkout latency", null, service, reporter, Priority.P1, Severity.SEV1);
        ReflectionTestUtils.setField(incident, "id", UUID.randomUUID());
        incident.setSlaStartedAt(startedAt);
        incident.setSlaResolutionTargetAt(startedAt.plus(Duration.ofMinutes(30)));
        incident.setSlaState(SlaState.ON_TRACK);
        return incident;
    }

    private Service service(String name) {
        Service created = new Service(name, null, null);
        ReflectionTestUtils.setField(created, "id", UUID.randomUUID());
        return created;
    }

    private User user(String email) {
        User created = new User("Dev Reporter", email, "hash", Role.OPERATOR);
        ReflectionTestUtils.setField(created, "id", UUID.randomUUID());
        return created;
    }

    /** A clock the test moves forward, so "the deadline passed" is deterministic. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
