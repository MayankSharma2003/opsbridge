package io.opsbridge.service;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.SlaPolicy;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.entity.IncidentActivity;
import io.opsbridge.domain.enums.ActivityEventType;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.repository.IncidentActivityRepository;
import io.opsbridge.repository.IncidentRepository;
import io.opsbridge.repository.IncidentSpecifications;
import io.opsbridge.repository.ServiceRepository;
import io.opsbridge.repository.UserRepository;
import io.opsbridge.service.SlaCalculator.SlaEvaluation;
import io.opsbridge.service.SlaCalculator.SlaTargets;
import io.opsbridge.service.command.AssignIncidentCommand;
import io.opsbridge.service.command.CreateIncidentCommand;
import io.opsbridge.service.command.EscalateIncidentCommand;
import io.opsbridge.service.command.IncidentSearchCommand;
import io.opsbridge.service.command.TransitionIncidentCommand;
import io.opsbridge.service.command.UpdateIncidentCommand;
import io.opsbridge.support.audit.ActivityRecorder;
import io.opsbridge.support.notification.IncidentDeclaredEvent;
import io.opsbridge.support.realtime.IncidentChangedEvent;
import io.opsbridge.support.error.ResourceNotFoundException;
import io.opsbridge.support.error.StaleVersionException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

// Owns incident workflow: transitions, SLA evaluation, optimistic concurrency, audit trail. Every
// mutation checks the expected version before touching state and writes its activity row in the same transaction.
// Qualified to avoid colliding with the Service entity this class works with.
@org.springframework.stereotype.Service
public class IncidentService {

    private static final String PUBLIC_KEY_PREFIX = "INC-";

    private final IncidentRepository incidentRepository;
    private final IncidentActivityRepository incidentActivityRepository;
    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;
    private final IncidentStateMachine stateMachine;
    private final SlaCalculator slaCalculator;
    private final SlaPolicyResolver slaPolicyResolver;
    private final ActivityRecorder activityRecorder;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public IncidentService(
            IncidentRepository incidentRepository,
            IncidentActivityRepository incidentActivityRepository,
            UserRepository userRepository,
            ServiceRepository serviceRepository,
            IncidentStateMachine stateMachine,
            SlaCalculator slaCalculator,
            SlaPolicyResolver slaPolicyResolver,
            ActivityRecorder activityRecorder,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.incidentRepository = incidentRepository;
        this.incidentActivityRepository = incidentActivityRepository;
        this.userRepository = userRepository;
        this.serviceRepository = serviceRepository;
        this.stateMachine = stateMachine;
        this.slaCalculator = slaCalculator;
        this.slaPolicyResolver = slaPolicyResolver;
        this.activityRecorder = activityRecorder;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public Incident create(CreateIncidentCommand command) {
        Service service = loadService(command.serviceId());
        User reporter = loadUser(command.reporterId());
        Instant now = clock.instant();

        Incident incident = new Incident(
                nextPublicKey(),
                command.title(),
                command.description(),
                service,
                reporter,
                command.priority(),
                command.severity());
        incident.setSlaStartedAt(now);
        applySlaPolicy(incident);
        refreshSlaState(incident, now);

        Incident saved = incidentRepository.save(incident);
        activityRecorder.record(
                saved, reporter, ActivityEventType.CREATED, null, IncidentStatus.OPEN.name(), null);
        // Separate from publishChanged's event: id-only, after-commit, but "declared" and "changed"
        // are different facts and IncidentChangedEvent has no way to tell them apart.
        eventPublisher.publishEvent(new IncidentDeclaredEvent(saved.getId()));
        return saved;
    }

    @Transactional(readOnly = true)
    public Incident findById(UUID incidentId) {
        Incident incident = incidentRepository
                .findById(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident", incidentId));
        refreshSlaStateForRead(incident);
        return incident;
    }

    @Transactional(readOnly = true)
    public Page<Incident> list(IncidentSearchCommand search, Pageable pageable) {
        Page<Incident> page = incidentRepository.findAll(IncidentSpecifications.matching(search), pageable);
        page.forEach(this::refreshSlaStateForRead);
        return page;
    }

    // Immutable audit trail (spec 4.1), oldest first.
    @Transactional(readOnly = true)
    public Page<IncidentActivity> listActivity(UUID incidentId, Pageable pageable) {
        if (!incidentRepository.existsById(incidentId)) {
            throw new ResourceNotFoundException("Incident", incidentId);
        }
        return incidentActivityRepository.findByIncidentId(incidentId, pageable);
    }

    @Transactional
    public Incident update(UpdateIncidentCommand command) {
        Incident incident = loadForUpdate(command.incidentId(), command.expectedVersion());
        User actor = loadUser(command.actorId());

        // Each branch diffs before writing -- resending an unchanged value is a no-op, and a no-op
        // must not create audit noise or burn a version.
        if (command.title() != null && !command.title().equals(incident.getTitle())) {
            String previous = incident.getTitle();
            incident.setTitle(command.title());
            activityRecorder.recordFieldChange(
                    incident, actor, ActivityEventType.TITLE_CHANGED, "title", previous, command.title(), null);
        }
        if (command.description() != null && !command.description().equals(incident.getDescription())) {
            String previous = incident.getDescription();
            incident.setDescription(command.description());
            activityRecorder.recordFieldChange(
                    incident,
                    actor,
                    ActivityEventType.DESCRIPTION_CHANGED,
                    "description",
                    previous,
                    command.description(),
                    null);
        }
        if (command.serviceId() != null
                && !command.serviceId().equals(incident.getService().getId())) {
            String previous = incident.getService().getName();
            Service service = loadService(command.serviceId());
            incident.setService(service);
            activityRecorder.record(
                    incident, actor, ActivityEventType.SERVICE_LINKED, previous, service.getName(), null);
        }
        if (command.resolutionNotes() != null && !command.resolutionNotes().equals(incident.getResolutionNotes())) {
            String previous = incident.getResolutionNotes();
            incident.setResolutionNotes(command.resolutionNotes());
            activityRecorder.record(
                    incident,
                    actor,
                    ActivityEventType.RESOLUTION_NOTE_ADDED,
                    previous,
                    command.resolutionNotes(),
                    null);
        }

        return incidentRepository.saveAndFlush(incident);
    }

    @Transactional
    public Incident transition(TransitionIncidentCommand command) {
        Incident incident = loadForUpdate(command.incidentId(), command.expectedVersion());
        User actor = loadUser(command.actorId());

        IncidentStatus from = incident.getStatus();
        IncidentStatus to = command.targetStatus();
        stateMachine.assertCanTransition(from, to);

        Instant now = clock.instant();
        incident.setStatus(to);
        applyTransitionSlaEffects(incident, from, to, now);

        if (to == IncidentStatus.RESOLVED && command.resolutionNotes() != null) {
            incident.setResolutionNotes(command.resolutionNotes());
        }
        refreshSlaState(incident, now);

        activityRecorder.record(
                incident, actor, ActivityEventType.STATUS_CHANGED, from.name(), to.name(), command.comment());
        Incident saved = incidentRepository.saveAndFlush(incident);
        publishChanged(saved);
        return saved;
    }

    @Transactional
    public Incident assign(AssignIncidentCommand command) {
        Incident incident = loadForUpdate(command.incidentId(), command.expectedVersion());
        User actor = loadUser(command.actorId());

        User previousAssignee = incident.getAssignee();
        User newAssignee = command.assigneeId() == null ? null : loadUser(command.assigneeId());

        // No-op reassignment to the current owner is skipped -- recording it would add a misleading
        // history entry and bump the version, staling every other open form for nothing.
        if (Objects.equals(idOf(previousAssignee), idOf(newAssignee))) {
            return incident;
        }

        incident.setAssignee(newAssignee);

        ActivityEventType eventType =
                previousAssignee == null ? ActivityEventType.ASSIGNED : ActivityEventType.REASSIGNED;
        activityRecorder.recordFieldChange(
                incident,
                actor,
                eventType,
                "assignee",
                previousAssignee == null ? null : previousAssignee.getEmail(),
                newAssignee == null ? null : newAssignee.getEmail(),
                null);

        Incident saved = incidentRepository.saveAndFlush(incident);
        publishChanged(saved);
        return saved;
    }

    // Escalation recomputes SLA targets from the original SLA start, not now -- resetting the clock
    // on a tighter policy could hide a breach that already happened.
    @Transactional
    public Incident escalate(EscalateIncidentCommand command) {
        Incident incident = loadForUpdate(command.incidentId(), command.expectedVersion());
        User actor = loadUser(command.actorId());
        boolean changed = false;

        if (command.priority() != null && command.priority() != incident.getPriority()) {
            String previous = incident.getPriority().name();
            incident.setPriority(command.priority());
            activityRecorder.record(
                    incident,
                    actor,
                    ActivityEventType.PRIORITY_CHANGED,
                    previous,
                    command.priority().name(),
                    command.reason());
            changed = true;
        }
        if (command.severity() != null && command.severity() != incident.getSeverity()) {
            String previous = incident.getSeverity().name();
            incident.setSeverity(command.severity());
            activityRecorder.record(
                    incident,
                    actor,
                    ActivityEventType.SEVERITY_CHANGED,
                    previous,
                    command.severity().name(),
                    command.reason());
            changed = true;
        }

        if (changed) {
            applySlaPolicy(incident);
            refreshSlaState(incident, clock.instant());
        }
        return incidentRepository.saveAndFlush(incident);
    }

    // Only status/assignment changes publish -- that's what the dashboard and incident list render,
    // so a title edit shouldn't interrupt every connected browser. Event carries only the id; the
    // listener re-reads after commit so nothing uncommitted leaks onto the wire.
    private void publishChanged(Incident incident) {
        eventPublisher.publishEvent(new IncidentChangedEvent(incident.getId()));
    }

    private void applyTransitionSlaEffects(Incident incident, IncidentStatus from, IncidentStatus to, Instant now) {
        // First triage sets the SLA response time; later returns to OPEN don't clear it -- it
        // genuinely happened once and can't un-happen.
        if (to == IncidentStatus.TRIAGED && incident.getSlaRespondedAt() == null) {
            incident.setSlaRespondedAt(now);
        }
        if (to == IncidentStatus.MITIGATED) {
            incident.setSlaMitigatedAt(now);
        }
        // Reopening a mitigated incident clears that milestone, so its target is outstanding again.
        if (from == IncidentStatus.MITIGATED && to == IncidentStatus.INVESTIGATING) {
            incident.setSlaMitigatedAt(null);
        }
        if (to == IncidentStatus.RESOLVED) {
            incident.setSlaResolvedAt(now);
        }
    }

    private void applySlaPolicy(Incident incident) {
        SlaPolicy policy = slaPolicyResolver.resolve(incident.getPriority(), incident.getSeverity());
        incident.setSlaPolicy(policy);
        SlaTargets targets = slaCalculator.computeTargets(policy, incident.getSlaStartedAt());
        incident.setSlaResponseTargetAt(targets.responseTargetAt());
        incident.setSlaMitigationTargetAt(targets.mitigationTargetAt());
        incident.setSlaResolutionTargetAt(targets.resolutionTargetAt());
    }

    private void refreshSlaState(Incident incident, Instant now) {
        SlaEvaluation evaluation = slaCalculator.evaluate(incident, now);
        incident.setSlaState(evaluation.state());
        incident.setSlaBreachedAt(evaluation.breachedAt());
    }

    // SLA state is time-dependent, so the persisted column can go stale between writes; reads
    // refresh it in memory only since the transaction is read-only.
    private void refreshSlaStateForRead(Incident incident) {
        refreshSlaState(incident, clock.instant());
    }

    private Incident loadForUpdate(UUID incidentId, int expectedVersion) {
        Incident incident = incidentRepository
                .findById(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident", incidentId));
        if (incident.getVersion() != expectedVersion) {
            throw new StaleVersionException(expectedVersion, incident.getVersion());
        }
        return incident;
    }

    private static UUID idOf(User user) {
        return user == null ? null : user.getId();
    }

    private User loadUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    private Service loadService(UUID serviceId) {
        return serviceRepository
                .findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service", serviceId));
    }

    private String nextPublicKey() {
        return PUBLIC_KEY_PREFIX + incidentRepository.nextPublicKeySequenceValue();
    }
}
