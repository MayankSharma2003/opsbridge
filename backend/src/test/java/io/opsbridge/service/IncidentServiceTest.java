package io.opsbridge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.entity.IncidentActivity;
import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.SlaPolicy;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.ActivityEventType;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.domain.enums.SlaState;
import io.opsbridge.repository.IncidentActivityRepository;
import io.opsbridge.repository.IncidentRepository;
import io.opsbridge.repository.ServiceRepository;
import io.opsbridge.repository.SlaPolicyRepository;
import io.opsbridge.repository.UserRepository;
import io.opsbridge.service.command.AssignIncidentCommand;
import io.opsbridge.service.command.CreateIncidentCommand;
import io.opsbridge.service.command.EscalateIncidentCommand;
import io.opsbridge.service.command.IncidentSearchCommand;
import io.opsbridge.service.command.TransitionIncidentCommand;
import io.opsbridge.service.command.UpdateIncidentCommand;
import io.opsbridge.support.audit.ActivityRecorder;
import io.opsbridge.support.notification.IncidentDeclaredEvent;
import io.opsbridge.support.realtime.IncidentChangedEvent;
import io.opsbridge.support.error.IllegalStatusTransitionException;
import io.opsbridge.support.error.ResourceNotFoundException;
import io.opsbridge.support.error.StaleVersionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

class IncidentServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-01T09:00:00Z");

    private IncidentRepository incidentRepository;
    private IncidentActivityRepository incidentActivityRepository;
    private UserRepository userRepository;
    private ServiceRepository serviceRepository;
    private SlaPolicyRepository slaPolicyRepository;
    private ActivityRecorder activityRecorder;
    private ApplicationEventPublisher eventPublisher;

    private User reporter;
    private User operator;
    private Service service;

    private IncidentService incidentService;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        incidentActivityRepository = mock(IncidentActivityRepository.class);
        userRepository = mock(UserRepository.class);
        serviceRepository = mock(ServiceRepository.class);
        slaPolicyRepository = mock(SlaPolicyRepository.class);
        activityRecorder = mock(ActivityRecorder.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        incidentService = new IncidentService(
                incidentRepository,
                incidentActivityRepository,
                userRepository,
                serviceRepository,
                new IncidentStateMachine(),
                new SlaCalculator(),
                new SlaPolicyResolver(slaPolicyRepository),
                activityRecorder,
                eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));

        reporter = user("reporter@opsbridge.local", Role.OPERATOR);
        operator = user("operator@opsbridge.local", Role.OPERATOR);
        service = service("Checkout API");

        when(incidentRepository.nextPublicKeySequenceValue()).thenReturn(1000L);
        when(incidentRepository.save(any(Incident.class))).thenAnswer(call -> call.getArgument(0));
        when(incidentRepository.saveAndFlush(any(Incident.class))).thenAnswer(call -> call.getArgument(0));
        when(serviceRepository.findById(service.getId())).thenReturn(Optional.of(service));
        when(userRepository.findById(reporter.getId())).thenReturn(Optional.of(reporter));
        when(userRepository.findById(operator.getId())).thenReturn(Optional.of(operator));
        stubPolicy(Priority.P1, 5, 30, 240);
        stubPolicy(Priority.P2, 15, 60, 480);
    }

    @Test
    void create_startsOpenWithSlaTargetsAndAnActivityRow() {
        Incident incident = incidentService.create(createCommand(Priority.P2, Severity.SEV3));

        assertThat(incident.getPublicKey()).isEqualTo("INC-1000");
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getSlaStartedAt()).isEqualTo(NOW);
        assertThat(incident.getSlaResponseTargetAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(incident.getSlaMitigationTargetAt()).isEqualTo(NOW.plus(Duration.ofMinutes(60)));
        assertThat(incident.getSlaResolutionTargetAt()).isEqualTo(NOW.plus(Duration.ofMinutes(480)));
        assertThat(incident.getSlaState()).isEqualTo(SlaState.ON_TRACK);

        verify(activityRecorder)
                .record(eq(incident), eq(reporter), eq(ActivityEventType.CREATED), eq(null), eq("OPEN"), eq(null));
    }

    @Test
    void create_survivesAMissingSlaPolicyWithoutTargets() {
        when(slaPolicyRepository.findByPriorityAndSeverityAndActiveTrue(Priority.P4, Severity.SEV4))
                .thenReturn(Optional.empty());
        when(slaPolicyRepository.findByPriorityAndSeverityIsNullAndActiveTrue(Priority.P4))
                .thenReturn(Optional.empty());

        Incident incident = incidentService.create(createCommand(Priority.P4, Severity.SEV4));

        assertThat(incident.getSlaPolicy()).isNull();
        assertThat(incident.getSlaResponseTargetAt()).isNull();
        assertThat(incident.getSlaState()).isEqualTo(SlaState.ON_TRACK);
    }

    @Test
    void create_prefersAnExactSeverityMatchOverThePriorityCatchAll() {
        // Overrides just the SEV1 case on top of setUp()'s broad P2 catch-all stub.
        SlaPolicy exact = new SlaPolicy(Priority.P2, Severity.SEV1, 10, 30, 240);
        when(slaPolicyRepository.findByPriorityAndSeverityAndActiveTrue(Priority.P2, Severity.SEV1))
                .thenReturn(Optional.of(exact));

        Incident incident = incidentService.create(createCommand(Priority.P2, Severity.SEV1));

        // SlaPolicyResolverTest proves the resolver prefers an exact match; this proves create() actually persists it.
        assertThat(incident.getSlaPolicy()).isEqualTo(exact);
        assertThat(incident.getSlaResponseTargetAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(incident.getSlaMitigationTargetAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(incident.getSlaResolutionTargetAt()).isEqualTo(NOW.plus(Duration.ofMinutes(240)));
    }

    @Test
    void create_rejectsAnUnknownService() {
        UUID missingService = UUID.randomUUID();
        when(serviceRepository.findById(missingService)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> incidentService.create(new CreateIncidentCommand(
                        "Title", null, missingService, reporter.getId(), Priority.P1, Severity.SEV1)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(activityRecorder);
    }

    @Test
    void transition_recordsFirstTriageAsTheSlaResponse() {
        Incident incident = storedIncident(IncidentStatus.OPEN, 0);

        Incident result = incidentService.transition(
                new TransitionIncidentCommand(
                        incident.getId(), 0, operator.getId(), IncidentStatus.TRIAGED, "picked up", null));

        assertThat(result.getStatus()).isEqualTo(IncidentStatus.TRIAGED);
        assertThat(result.getSlaRespondedAt()).isEqualTo(NOW);
        verify(activityRecorder)
                .record(
                        eq(incident),
                        eq(operator),
                        eq(ActivityEventType.STATUS_CHANGED),
                        eq("OPEN"),
                        eq("TRIAGED"),
                        eq("picked up"));
    }

    @Test
    void transition_rejectsAnIllegalJumpWithoutWritingAnythingOrAuditing() {
        Incident incident = storedIncident(IncidentStatus.OPEN, 0);

        assertThatThrownBy(() -> incidentService.transition(new TransitionIncidentCommand(
                        incident.getId(), 0, operator.getId(), IncidentStatus.RESOLVED, null, null)))
                .isInstanceOf(IllegalStatusTransitionException.class);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        verifyNoInteractions(activityRecorder);
        verify(incidentRepository, never()).saveAndFlush(any());
    }

    @Test
    void transition_rejectsAStaleExpectedVersionBeforeTouchingState() {
        Incident incident = storedIncident(IncidentStatus.OPEN, 4);

        assertThatThrownBy(() -> incidentService.transition(new TransitionIncidentCommand(
                        incident.getId(), 2, operator.getId(), IncidentStatus.TRIAGED, null, null)))
                .isInstanceOf(StaleVersionException.class)
                .hasMessageContaining("expected version 2")
                .hasMessageContaining("current version 4");

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        verifyNoInteractions(activityRecorder);
        verify(incidentRepository, never()).saveAndFlush(any());
    }

    @Test
    void transition_stampsMitigationOnMitigated() {
        Incident incident = storedIncident(IncidentStatus.INVESTIGATING, 0);
        incident.setSlaRespondedAt(NOW.minus(Duration.ofMinutes(1)));

        Incident result = incidentService.transition(new TransitionIncidentCommand(
                incident.getId(), 0, operator.getId(), IncidentStatus.MITIGATED, null, null));

        assertThat(result.getSlaMitigatedAt()).isEqualTo(NOW);
    }

    @Test
    void transition_clearsMitigationWhenAMitigatedIncidentIsReopened() {
        Incident incident = storedIncident(IncidentStatus.MITIGATED, 0);
        incident.setSlaRespondedAt(NOW.minus(Duration.ofMinutes(10)));
        incident.setSlaMitigatedAt(NOW.minus(Duration.ofMinutes(2)));

        Incident result = incidentService.transition(new TransitionIncidentCommand(
                incident.getId(), 0, operator.getId(), IncidentStatus.INVESTIGATING, null, null));

        assertThat(result.getSlaMitigatedAt()).isNull();
    }

    @Test
    void transition_keepsTheResponseTimestampWhenAnIncidentReturnsToOpen() {
        Instant respondedAt = NOW.minus(Duration.ofMinutes(30));
        Incident incident = storedIncident(IncidentStatus.INVESTIGATING, 0);
        incident.setSlaRespondedAt(respondedAt);

        Incident result = incidentService.transition(new TransitionIncidentCommand(
                incident.getId(), 0, operator.getId(), IncidentStatus.OPEN, null, null));

        assertThat(result.getSlaRespondedAt()).isEqualTo(respondedAt);
    }

    @Test
    void transition_resolvesWithNotesAndMarksTheSlaMet() {
        Incident incident = storedIncident(IncidentStatus.MITIGATED, 0);
        incident.setSlaRespondedAt(NOW.minus(Duration.ofMinutes(10)));
        incident.setSlaMitigatedAt(NOW.minus(Duration.ofMinutes(5)));

        Incident result = incidentService.transition(new TransitionIncidentCommand(
                incident.getId(), 0, operator.getId(), IncidentStatus.RESOLVED, null, "Rolled back deploy 42."));

        assertThat(result.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(result.getSlaResolvedAt()).isEqualTo(NOW);
        assertThat(result.getResolutionNotes()).isEqualTo("Rolled back deploy 42.");
        assertThat(result.getSlaState()).isEqualTo(SlaState.MET);
    }

    @Test
    void transition_marksTheSlaBreachedWhenATargetHasAlreadyPassed() {
        Incident incident = storedIncident(IncidentStatus.OPEN, 0);
        // P1's 5-minute response target, against a start that's now an hour old.
        incident.setSlaStartedAt(NOW.minus(Duration.ofHours(1)));
        incident.setSlaResponseTargetAt(NOW.minus(Duration.ofMinutes(55)));

        Incident result = incidentService.transition(new TransitionIncidentCommand(
                incident.getId(), 0, operator.getId(), IncidentStatus.TRIAGED, null, null));

        assertThat(result.getSlaState()).isEqualTo(SlaState.BREACHED);
        assertThat(result.getSlaBreachedAt()).isEqualTo(NOW.minus(Duration.ofMinutes(55)));
    }

    @Test
    void transition_rejectsAnUnknownIncident() {
        UUID missing = UUID.randomUUID();
        when(incidentRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> incidentService.transition(new TransitionIncidentCommand(
                        missing, 0, operator.getId(), IncidentStatus.TRIAGED, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void assign_recordsAnAssignmentWhenTheIncidentWasUnassigned() {
        Incident incident = storedIncident(IncidentStatus.TRIAGED, 0);

        Incident result = incidentService.assign(
                new AssignIncidentCommand(incident.getId(), 0, reporter.getId(), operator.getId()));

        assertThat(result.getAssignee()).isEqualTo(operator);
        verify(activityRecorder)
                .recordFieldChange(
                        eq(incident),
                        eq(reporter),
                        eq(ActivityEventType.ASSIGNED),
                        eq("assignee"),
                        eq(null),
                        eq("operator@opsbridge.local"),
                        eq(null));
    }

    @Test
    void assign_recordsAReassignmentWhenTheIncidentAlreadyHadAnOwner() {
        Incident incident = storedIncident(IncidentStatus.TRIAGED, 0);
        incident.setAssignee(reporter);

        incidentService.assign(new AssignIncidentCommand(incident.getId(), 0, reporter.getId(), operator.getId()));

        verify(activityRecorder)
                .recordFieldChange(
                        eq(incident),
                        eq(reporter),
                        eq(ActivityEventType.REASSIGNED),
                        eq("assignee"),
                        eq("reporter@opsbridge.local"),
                        eq("operator@opsbridge.local"),
                        eq(null));
    }

    @Test
    void assign_unassignsOnANullAssignee() {
        Incident incident = storedIncident(IncidentStatus.TRIAGED, 0);
        incident.setAssignee(operator);

        Incident result =
                incidentService.assign(new AssignIncidentCommand(incident.getId(), 0, reporter.getId(), null));

        assertThat(result.getAssignee()).isNull();
        verify(activityRecorder)
                .recordFieldChange(
                        eq(incident),
                        eq(reporter),
                        eq(ActivityEventType.REASSIGNED),
                        eq("assignee"),
                        eq("operator@opsbridge.local"),
                        eq(null),
                        eq(null));
    }

    @Test
    void assign_isANoOpWhenTheIncidentAlreadyHasThatAssignee() {
        Incident incident = storedIncident(IncidentStatus.TRIAGED, 0);
        incident.setAssignee(operator);

        Incident result = incidentService.assign(
                new AssignIncidentCommand(incident.getId(), 0, reporter.getId(), operator.getId()));

        assertThat(result.getAssignee()).isEqualTo(operator);
        // No audit noise or version bump -- a redundant re-assign shouldn't invalidate anyone's open form.
        verifyNoInteractions(activityRecorder);
        verify(incidentRepository, never()).saveAndFlush(any());
    }

    @Test
    void assign_rejectsAStaleExpectedVersion() {
        Incident incident = storedIncident(IncidentStatus.TRIAGED, 7);

        assertThatThrownBy(() -> incidentService.assign(
                        new AssignIncidentCommand(incident.getId(), 6, reporter.getId(), operator.getId())))
                .isInstanceOf(StaleVersionException.class);

        assertThat(incident.getAssignee()).isNull();
    }

    @Test
    void create_publishesADeclaredEventForNotification() {
        Incident incident = incidentService.create(createCommand(Priority.P2, Severity.SEV3));

        verify(eventPublisher).publishEvent(new IncidentDeclaredEvent(incident.getId()));
    }

    @Test
    void transition_publishesALiveEventForTheChangedIncident() {
        Incident incident = storedIncident(IncidentStatus.OPEN, 0);

        incidentService.transition(new TransitionIncidentCommand(
                incident.getId(), 0, operator.getId(), IncidentStatus.TRIAGED, null, null));

        verify(eventPublisher).publishEvent(new IncidentChangedEvent(incident.getId()));
    }

    @Test
    void assign_publishesALiveEventWhenTheAssigneeActuallyChanges() {
        Incident incident = storedIncident(IncidentStatus.TRIAGED, 0);

        incidentService.assign(
                new AssignIncidentCommand(incident.getId(), 0, reporter.getId(), operator.getId()));

        verify(eventPublisher).publishEvent(new IncidentChangedEvent(incident.getId()));
    }

    @Test
    void assign_publishesNothingForANoOpReassignment() {
        Incident incident = storedIncident(IncidentStatus.TRIAGED, 0);
        incident.setAssignee(operator);

        incidentService.assign(
                new AssignIncidentCommand(incident.getId(), 0, reporter.getId(), operator.getId()));

        // Nothing changed, so nothing should interrupt anyone's screen.
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void transition_publishesNothingWhenTheTransitionIsRejected() {
        Incident incident = storedIncident(IncidentStatus.RESOLVED, 0);

        assertThatThrownBy(() -> incidentService.transition(new TransitionIncidentCommand(
                        incident.getId(), 0, operator.getId(), IncidentStatus.TRIAGED, null, null)))
                .isInstanceOf(IllegalStatusTransitionException.class);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void escalate_recomputesTargetsFromTheOriginalStartNotFromNow() {
        Incident incident = storedIncident(IncidentStatus.INVESTIGATING, 0);
        Instant startedAt = NOW.minus(Duration.ofMinutes(10));
        incident.setSlaStartedAt(startedAt);
        incident.setSlaRespondedAt(startedAt.plus(Duration.ofMinutes(1)));

        Incident result = incidentService.escalate(new EscalateIncidentCommand(
                incident.getId(), 0, operator.getId(), Priority.P1, null, "customer impact"));

        assertThat(result.getPriority()).isEqualTo(Priority.P1);
        // Escalation must not reset the clock -- the 5-minute P1 deadline is already past and shouldn't be hidden.
        assertThat(result.getSlaResponseTargetAt()).isEqualTo(startedAt.plus(Duration.ofMinutes(5)));
        assertThat(result.getSlaMitigationTargetAt()).isEqualTo(startedAt.plus(Duration.ofMinutes(30)));
        verify(activityRecorder)
                .record(
                        eq(incident),
                        eq(operator),
                        eq(ActivityEventType.PRIORITY_CHANGED),
                        eq("P2"),
                        eq("P1"),
                        eq("customer impact"));
    }

    @Test
    void escalate_recordsSeverityChangesSeparately() {
        Incident incident = storedIncident(IncidentStatus.INVESTIGATING, 0);

        incidentService.escalate(new EscalateIncidentCommand(
                incident.getId(), 0, operator.getId(), null, Severity.SEV1, "data loss risk"));

        verify(activityRecorder)
                .record(
                        eq(incident),
                        eq(operator),
                        eq(ActivityEventType.SEVERITY_CHANGED),
                        eq("SEV3"),
                        eq("SEV1"),
                        eq("data loss risk"));
    }

    @Test
    void escalate_isANoOpWhenNothingActuallyChanges() {
        Incident incident = storedIncident(IncidentStatus.INVESTIGATING, 0);

        incidentService.escalate(new EscalateIncidentCommand(
                incident.getId(), 0, operator.getId(), Priority.P2, Severity.SEV3, "no change"));

        verifyNoInteractions(activityRecorder);
    }

    @Test
    void update_relinksTheServiceAndAuditsTheChange() {
        Incident incident = storedIncident(IncidentStatus.TRIAGED, 0);
        Service payments = service("Payments API");
        when(serviceRepository.findById(payments.getId())).thenReturn(Optional.of(payments));

        Incident result = incidentService.update(new UpdateIncidentCommand(
                incident.getId(), 0, operator.getId(), "New title", null, payments.getId(), null));

        assertThat(result.getTitle()).isEqualTo("New title");
        assertThat(result.getService()).isEqualTo(payments);
        verify(activityRecorder)
                .record(
                        eq(incident),
                        eq(operator),
                        eq(ActivityEventType.SERVICE_LINKED),
                        eq("Checkout API"),
                        eq("Payments API"),
                        eq(null));
    }

    @Test
    void update_auditsTitleAndDescriptionChangesWithTheirOldValues() {
        Incident incident = storedIncident(IncidentStatus.TRIAGED, 0);
        incident.setDescription("Original description");

        Incident result = incidentService.update(new UpdateIncidentCommand(
                incident.getId(), 0, operator.getId(), "Checkout is down", "Rewritten description", null, null));

        assertThat(result.getTitle()).isEqualTo("Checkout is down");
        assertThat(result.getDescription()).isEqualTo("Rewritten description");
        verify(activityRecorder)
                .recordFieldChange(
                        eq(incident),
                        eq(operator),
                        eq(ActivityEventType.TITLE_CHANGED),
                        eq("title"),
                        eq("Checkout latency spike"),
                        eq("Checkout is down"),
                        eq(null));
        verify(activityRecorder)
                .recordFieldChange(
                        eq(incident),
                        eq(operator),
                        eq(ActivityEventType.DESCRIPTION_CHANGED),
                        eq("description"),
                        eq("Original description"),
                        eq("Rewritten description"),
                        eq(null));
    }

    @Test
    void update_auditsResolutionNotesWithThePreviousText() {
        Incident incident = storedIncident(IncidentStatus.MITIGATED, 0);
        incident.setResolutionNotes("Investigating.");

        incidentService.update(new UpdateIncidentCommand(
                incident.getId(), 0, operator.getId(), null, null, null, "Rolled back deploy 42."));

        verify(activityRecorder)
                .record(
                        eq(incident),
                        eq(operator),
                        eq(ActivityEventType.RESOLUTION_NOTE_ADDED),
                        eq("Investigating."),
                        eq("Rolled back deploy 42."),
                        eq(null));
    }

    @Test
    void update_recordsNothingWhenEveryFieldIsResentUnchanged() {
        Incident incident = storedIncident(IncidentStatus.TRIAGED, 0);
        incident.setDescription("Original description");
        incident.setResolutionNotes("Investigating.");

        incidentService.update(new UpdateIncidentCommand(
                incident.getId(),
                0,
                operator.getId(),
                "Checkout latency spike",
                "Original description",
                service.getId(),
                "Investigating."));

        verifyNoInteractions(activityRecorder);
    }

    @Test
    void findById_refreshesStaleSlaStateForReaders() {
        // Persisted state is stale since nobody's touched it since the deadline passed; reads must reflect the breach.
        Incident incident = storedIncident(IncidentStatus.OPEN, 0);
        incident.setSlaStartedAt(NOW.minus(Duration.ofHours(2)));
        incident.setSlaResponseTargetAt(NOW.minus(Duration.ofHours(1)));
        incident.setSlaState(SlaState.ON_TRACK);

        Incident result = incidentService.findById(incident.getId());

        assertThat(result.getSlaState()).isEqualTo(SlaState.BREACHED);
        assertThat(result.getSlaBreachedAt()).isEqualTo(NOW.minus(Duration.ofHours(1)));
    }

    @Test
    void findById_rejectsAnUnknownIncident() {
        UUID missing = UUID.randomUUID();
        when(incidentRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> incidentService.findById(missing)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void list_delegatesToTheRepositoryWithASpecificationAndReturnsItsPage() {
        Incident incident = storedIncident(IncidentStatus.OPEN, 0);
        Page<Incident> expected = new PageImpl<>(List.of(incident));
        Pageable pageable = PageRequest.of(0, 25);
        IncidentSearchCommand search =
                new IncidentSearchCommand("checkout", IncidentStatus.OPEN, Priority.P1, null, null, null, null);
        when(incidentRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(expected);

        Page<Incident> result = incidentService.list(search, pageable);

        assertThat(result).isSameAs(expected);
        verify(incidentRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void list_refreshesStaleSlaStateForEveryRow() {
        // Same staleness fix as findById, applied per row of the list.
        Incident stale = storedIncident(IncidentStatus.OPEN, 0);
        stale.setSlaStartedAt(NOW.minus(Duration.ofHours(2)));
        stale.setSlaResponseTargetAt(NOW.minus(Duration.ofHours(1)));
        stale.setSlaState(SlaState.ON_TRACK);
        Pageable pageable = PageRequest.of(0, 25);
        when(incidentRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(stale)));

        Page<Incident> result = incidentService.list(emptySearch(), pageable);

        assertThat(result.getContent().getFirst().getSlaState()).isEqualTo(SlaState.BREACHED);
    }

    @Test
    void listActivity_delegatesToTheRepositoryWhenTheIncidentExists() {
        Incident incident = storedIncident(IncidentStatus.OPEN, 0);
        Pageable pageable = PageRequest.of(0, 50);
        Page<IncidentActivity> expected = new PageImpl<>(List.of());
        when(incidentRepository.existsById(incident.getId())).thenReturn(true);
        when(incidentActivityRepository.findByIncidentId(incident.getId(), pageable)).thenReturn(expected);

        Page<IncidentActivity> result = incidentService.listActivity(incident.getId(), pageable);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void listActivity_rejectsAnUnknownIncidentWithoutQueryingActivity() {
        UUID missing = UUID.randomUUID();
        when(incidentRepository.existsById(missing)).thenReturn(false);

        assertThatThrownBy(() -> incidentService.listActivity(missing, PageRequest.of(0, 50)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(incidentActivityRepository);
    }

    private IncidentSearchCommand emptySearch() {
        return new IncidentSearchCommand(null, null, null, null, null, null, null);
    }

    private CreateIncidentCommand createCommand(Priority priority, Severity severity) {
        return new CreateIncidentCommand(
                "Checkout latency spike", "p99 above 3s", service.getId(), reporter.getId(), priority, severity);
    }

    /** A P2/SEV3 incident already persisted at the given status and version. */
    private Incident storedIncident(IncidentStatus status, int version) {
        Incident incident =
                new Incident("INC-1000", "Checkout latency spike", null, service, reporter, Priority.P2, Severity.SEV3);
        ReflectionTestUtils.setField(incident, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(incident, "version", version);
        incident.setStatus(status);
        incident.setSlaStartedAt(NOW);
        when(incidentRepository.findById(incident.getId())).thenReturn(Optional.of(incident));
        return incident;
    }

    private void stubPolicy(Priority priority, int response, int mitigation, int resolution) {
        SlaPolicy policy = new SlaPolicy(priority, null, response, mitigation, resolution);
        when(slaPolicyRepository.findByPriorityAndSeverityAndActiveTrue(eq(priority), any()))
                .thenReturn(Optional.empty());
        when(slaPolicyRepository.findByPriorityAndSeverityIsNullAndActiveTrue(priority))
                .thenReturn(Optional.of(policy));
    }

    private static User user(String email, Role role) {
        User user = new User("Test " + email, email, "hash", role);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private static Service service(String name) {
        Service service = new Service(name, null, null);
        ReflectionTestUtils.setField(service, "id", UUID.randomUUID());
        return service;
    }
}
