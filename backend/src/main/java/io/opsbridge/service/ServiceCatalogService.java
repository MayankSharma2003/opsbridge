package io.opsbridge.service;

import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.ActivityEventType;
import io.opsbridge.domain.enums.ServiceStatus;
import io.opsbridge.repository.IncidentRepository;
import io.opsbridge.repository.IncidentRepository.ServiceOpenCount;
import io.opsbridge.repository.ServiceRepository;
import io.opsbridge.repository.ServiceSpecifications;
import io.opsbridge.repository.UserRepository;
import io.opsbridge.service.command.ArchiveServiceCommand;
import io.opsbridge.service.command.CreateServiceCommand;
import io.opsbridge.service.command.ServiceSearchCommand;
import io.opsbridge.service.command.UpdateServiceCommand;
import io.opsbridge.support.audit.ActivityRecorder;
import io.opsbridge.support.error.ResourceNotFoundException;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

// Admin-only catalog ops, plus a paginated/filtered/searchable list open to any role (spec 4.3, 4.5).
// Every mutation writes one activity row per changed field, in the same transaction as the change.
// No optimistic version here -- concurrent edits are last-write-wins, but both writes land in the
// audit trail so a clobber is at least reconstructable.
// Qualified to avoid colliding with the Service entity this class manages.
@org.springframework.stereotype.Service
public class ServiceCatalogService {

    private final ServiceRepository serviceRepository;
    private final UserRepository userRepository;
    private final IncidentRepository incidentRepository;
    private final IncidentStateMachine stateMachine;
    private final ActivityRecorder activityRecorder;

    public ServiceCatalogService(
            ServiceRepository serviceRepository,
            UserRepository userRepository,
            IncidentRepository incidentRepository,
            IncidentStateMachine stateMachine,
            ActivityRecorder activityRecorder) {
        this.serviceRepository = serviceRepository;
        this.userRepository = userRepository;
        this.incidentRepository = incidentRepository;
        this.stateMachine = stateMachine;
        this.activityRecorder = activityRecorder;
    }

    @Transactional
    public ServiceWithOpenIncidents create(CreateServiceCommand command) {
        User actor = loadUser(command.actorId());
        User owner = loadUser(command.ownerId());
        Service service = new Service(command.name(), command.description(), owner);
        service.setTeam(command.team());
        service.setEscalationContact(command.escalationContact());
        service.setAvailabilityTarget(command.availabilityTarget());

        // Saved first so the activity row references an id that already exists.
        Service saved = serviceRepository.save(service);
        activityRecorder.recordServiceEvent(
                saved, actor, ActivityEventType.SERVICE_CREATED, null, saved.getName(), null);
        // Brand-new service can't have incidents yet, so zero is known without querying.
        return new ServiceWithOpenIncidents(saved, 0L, 0L);
    }

    @Transactional(readOnly = true)
    public Service findById(UUID serviceId) {
        return serviceRepository
                .findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service", serviceId));
    }

    // Catalog list with each row's live open-incident count; "open" = non-terminal, same as the dashboard.
    @Transactional(readOnly = true)
    public Page<ServiceWithOpenIncidents> list(ServiceSearchCommand search, Pageable pageable) {
        Page<Service> page = serviceRepository.findAll(ServiceSpecifications.matching(search), pageable);
        Map<UUID, OpenTally> tallies = openIncidentTallies(page.map(Service::getId).toList());
        return page.map(service -> withCounts(service, tallies));
    }

    /** One service with its open-incident counts, for the catalog drawer. */
    @Transactional(readOnly = true)
    public ServiceWithOpenIncidents findByIdWithOpenCount(UUID serviceId) {
        Service service = findById(serviceId);
        return withCounts(service, openIncidentTallies(List.of(service.getId())));
    }

    // Zero-filled -- no open incidents means no GROUP BY row comes back for it.
    private ServiceWithOpenIncidents withCounts(Service service, Map<UUID, OpenTally> tallies) {
        OpenTally tally = tallies.getOrDefault(service.getId(), OpenTally.EMPTY);
        return new ServiceWithOpenIncidents(service, tally.total(), tally.urgent());
    }

    private Map<UUID, OpenTally> openIncidentTallies(List<UUID> serviceIds) {
        if (serviceIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, OpenTally> tallies = new java.util.HashMap<>();
        for (ServiceOpenCount row : incidentRepository.countOpenIncidentsByService(serviceIds, terminalStatuses())) {
            OpenTally current = tallies.getOrDefault(row.getServiceId(), OpenTally.EMPTY);
            long urgent = isUrgent(row.getPriority()) ? row.getCount() : 0L;
            tallies.put(row.getServiceId(), new OpenTally(current.total() + row.getCount(), current.urgent() + urgent));
        }
        return tallies;
    }

    // P1/P2 = critical health signal here, matching the incident list's own bar.
    private static boolean isUrgent(Priority priority) {
        return priority == Priority.P1 || priority == Priority.P2;
    }

    private record OpenTally(long total, long urgent) {
        private static final OpenTally EMPTY = new OpenTally(0L, 0L);
    }

    private Set<IncidentStatus> terminalStatuses() {
        return Arrays.stream(IncidentStatus.values())
                .filter(stateMachine::isTerminal)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Transactional
    public ServiceWithOpenIncidents update(UpdateServiceCommand command) {
        Service service = findById(command.serviceId());
        User actor = loadUser(command.actorId());

        applyChange(service, actor, "name", command.name(), service::getName, service::setName);
        applyChange(
                service, actor, "description", command.description(), service::getDescription, service::setDescription);
        applyChange(service, actor, "team", command.team(), service::getTeam, service::setTeam);
        applyChange(
                service,
                actor,
                "escalationContact",
                command.escalationContact(),
                service::getEscalationContact,
                service::setEscalationContact);

        if (command.ownerId() != null
                && !command.ownerId().equals(service.getOwner().getId())) {
            String previous = service.getOwner().getEmail();
            User owner = loadUser(command.ownerId());
            service.setOwner(owner);
            activityRecorder.recordServiceFieldChange(
                    service, actor, ActivityEventType.SERVICE_UPDATED, "owner", previous, owner.getEmail(), null);
        }
        // compareTo not equals -- BigDecimal treats 99.9 and 99.90 as different, and that's not a
        // real change worth an audit row.
        if (command.availabilityTarget() != null
                && !sameAmount(service.getAvailabilityTarget(), command.availabilityTarget())) {
            BigDecimal previous = service.getAvailabilityTarget();
            service.setAvailabilityTarget(command.availabilityTarget());
            activityRecorder.recordServiceFieldChange(
                    service,
                    actor,
                    ActivityEventType.SERVICE_UPDATED,
                    "availabilityTarget",
                    previous == null ? null : previous.toPlainString(),
                    command.availabilityTarget().toPlainString(),
                    null);
        }

        return withCounts(serviceRepository.saveAndFlush(service), openIncidentTallies(List.of(service.getId())));
    }

    // Archived, not deleted -- incident history keeps pointing at a real service.
    @Transactional
    public ServiceWithOpenIncidents archive(ArchiveServiceCommand command) {
        Service service = findById(command.serviceId());
        User actor = loadUser(command.actorId());

        if (service.getStatus() == ServiceStatus.ARCHIVED) {
            return withCounts(service, openIncidentTallies(List.of(service.getId())));
        }

        ServiceStatus previous = service.getStatus();
        service.setStatus(ServiceStatus.ARCHIVED);
        activityRecorder.recordServiceFieldChange(
                service,
                actor,
                ActivityEventType.SERVICE_ARCHIVED,
                "status",
                previous.name(),
                ServiceStatus.ARCHIVED.name(),
                null);
        return withCounts(serviceRepository.saveAndFlush(service), openIncidentTallies(List.of(service.getId())));
    }

    // Applies one nullable patch field, auditing only when the value actually changes.
    private void applyChange(
            Service service,
            User actor,
            String fieldName,
            String requested,
            Supplier<String> current,
            Consumer<String> setter) {
        if (requested == null || Objects.equals(requested, current.get())) {
            return;
        }
        String previous = current.get();
        setter.accept(requested);
        activityRecorder.recordServiceFieldChange(
                service, actor, ActivityEventType.SERVICE_UPDATED, fieldName, previous, requested, null);
    }

    private static boolean sameAmount(BigDecimal current, BigDecimal requested) {
        return current != null && current.compareTo(requested) == 0;
    }

    private User loadUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }
}
