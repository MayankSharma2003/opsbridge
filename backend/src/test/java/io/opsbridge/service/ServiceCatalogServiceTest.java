package io.opsbridge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.ActivityEventType;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.domain.enums.ServiceStatus;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.repository.IncidentRepository;
import io.opsbridge.repository.IncidentRepository.ServiceOpenCount;
import io.opsbridge.repository.ServiceRepository;
import io.opsbridge.repository.UserRepository;
import io.opsbridge.service.command.ArchiveServiceCommand;
import io.opsbridge.service.command.CreateServiceCommand;
import io.opsbridge.service.command.ServiceSearchCommand;
import io.opsbridge.service.command.UpdateServiceCommand;
import io.opsbridge.support.audit.ActivityRecorder;
import io.opsbridge.support.error.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

// The catalog is Admin-only and unversioned -- last-write-wins, with the audit trail making a
// clobber reconstructable. ServiceRepositoryTest covers whether list()'s Specification actually filters.
class ServiceCatalogServiceTest {

    private ServiceRepository serviceRepository;
    private UserRepository userRepository;
    private IncidentRepository incidentRepository;
    private ActivityRecorder activityRecorder;

    private User admin;
    private User owner;
    private Service service;

    private ServiceCatalogService catalog;

    @BeforeEach
    void setUp() {
        serviceRepository = mock(ServiceRepository.class);
        userRepository = mock(UserRepository.class);
        incidentRepository = mock(IncidentRepository.class);
        activityRecorder = mock(ActivityRecorder.class);
        catalog = new ServiceCatalogService(
                serviceRepository, userRepository, incidentRepository, new IncidentStateMachine(), activityRecorder);
        // Default: nothing open anywhere. Tests that care about the count override this.
        when(incidentRepository.countOpenIncidentsByService(anyCollection(), anyCollection()))
                .thenReturn(List.of());

        admin = user("admin@opsbridge.local", Role.ADMIN);
        owner = user("owner@opsbridge.local", Role.OPERATOR);
        service = storedService();

        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(serviceRepository.save(any(Service.class))).thenAnswer(call -> call.getArgument(0));
        when(serviceRepository.saveAndFlush(any(Service.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void list_delegatesToTheRepositoryWithASpecificationAndReturnsItsPage() {
        Page<Service> expected = new PageImpl<>(List.of(service));
        Pageable pageable = PageRequest.of(0, 25);
        ServiceSearchCommand search = new ServiceSearchCommand("checkout", ServiceStatus.ACTIVE, "Payments");
        when(serviceRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(expected);

        Page<ServiceWithOpenIncidents> result = catalog.list(search, pageable);

        assertThat(result.getContent()).extracting(ServiceWithOpenIncidents::service).containsExactly(service);
        verify(serviceRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void list_attachesEachServicesOpenIncidentCount() {
        Pageable pageable = PageRequest.of(0, 25);
        when(serviceRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(service)));
        when(incidentRepository.countOpenIncidentsByService(anyCollection(), anyCollection()))
                .thenReturn(List.of(
                        openCount(service.getId(), Priority.P1, 1L),
                        openCount(service.getId(), Priority.P3, 2L)));

        Page<ServiceWithOpenIncidents> result = catalog.list(new ServiceSearchCommand(null, null, null), pageable);

        // Rows are per (service, priority); summed into a total plus the P1/P2 subset.
        assertThat(result.getContent()).singleElement().satisfies(pair -> {
            assertThat(pair.openIncidentCount()).isEqualTo(3L);
            assertThat(pair.urgentOpenIncidentCount()).isEqualTo(1L);
        });
    }

    @Test
    void list_countsOnlyP1AndP2AsUrgent() {
        Pageable pageable = PageRequest.of(0, 25);
        when(serviceRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(service)));
        when(incidentRepository.countOpenIncidentsByService(anyCollection(), anyCollection()))
                .thenReturn(List.of(
                        openCount(service.getId(), Priority.P3, 4L), openCount(service.getId(), Priority.P4, 1L)));

        Page<ServiceWithOpenIncidents> result = catalog.list(new ServiceSearchCommand(null, null, null), pageable);

        assertThat(result.getContent()).singleElement().satisfies(pair -> {
            assertThat(pair.openIncidentCount()).isEqualTo(5L);
            assertThat(pair.urgentOpenIncidentCount()).isZero();
        });
    }

    @Test
    void list_zeroFillsAServiceWithNoOpenIncidents() {
        Pageable pageable = PageRequest.of(0, 25);
        when(serviceRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(service)));
        // GROUP BY returns no row for a service with nothing open.
        when(incidentRepository.countOpenIncidentsByService(anyCollection(), anyCollection()))
                .thenReturn(List.of());

        Page<ServiceWithOpenIncidents> result = catalog.list(new ServiceSearchCommand(null, null, null), pageable);

        assertThat(result.getContent()).singleElement().extracting(ServiceWithOpenIncidents::openIncidentCount)
                .isEqualTo(0L);
    }

    @Test
    void list_countsOnlyNonTerminalStatusesAsOpen() {
        Pageable pageable = PageRequest.of(0, 25);
        when(serviceRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(service)));

        catalog.list(new ServiceSearchCommand(null, null, null), pageable);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Collection<IncidentStatus>> statuses =
                org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(incidentRepository).countOpenIncidentsByService(anyCollection(), statuses.capture());
        assertThat(statuses.getValue()).containsExactlyInAnyOrder(IncidentStatus.RESOLVED, IncidentStatus.CANCELLED);
    }

    @Test
    void findByIdWithOpenCount_pairsTheServiceWithItsCount() {
        when(serviceRepository.findById(service.getId())).thenReturn(Optional.of(service));
        when(incidentRepository.countOpenIncidentsByService(anyCollection(), anyCollection()))
                .thenReturn(List.of(openCount(service.getId(), Priority.P2, 2L)));

        ServiceWithOpenIncidents result = catalog.findByIdWithOpenCount(service.getId());

        assertThat(result.service()).isSameAs(service);
        assertThat(result.openIncidentCount()).isEqualTo(2L);
        assertThat(result.urgentOpenIncidentCount()).isEqualTo(2L);
    }

    private static ServiceOpenCount openCount(UUID serviceId, Priority priority, long count) {
        return new ServiceOpenCount() {
            @Override
            public UUID getServiceId() {
                return serviceId;
            }

            @Override
            public Priority getPriority() {
                return priority;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    @Test
    void create_recordsTheCreationAgainstTheActingAdmin() {
        Service created = catalog
                .create(new CreateServiceCommand(
                        admin.getId(), "Payments API", "Takes money", owner.getId(), "Payments", null, null))
                .service();

        assertThat(created.getName()).isEqualTo("Payments API");
        assertThat(created.getOwner()).isEqualTo(owner);
        assertThat(created.getStatus()).isEqualTo(ServiceStatus.ACTIVE);
        verify(activityRecorder)
                .recordServiceEvent(
                        eq(created),
                        eq(admin),
                        eq(ActivityEventType.SERVICE_CREATED),
                        eq(null),
                        eq("Payments API"),
                        eq(null));
    }

    @Test
    void create_rejectsAnUnknownActorWithoutWritingAnything() {
        UUID ghost = UUID.randomUUID();
        when(userRepository.findById(ghost)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> catalog.create(
                        new CreateServiceCommand(ghost, "Payments API", null, owner.getId(), null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(serviceRepository, never()).save(any());
        verifyNoInteractions(activityRecorder);
    }

    @Test
    void update_writesOneRowPerChangedFieldWithOldAndNewValues() {
        catalog.update(new UpdateServiceCommand(
                service.getId(),
                admin.getId(),
                "Checkout API v2",
                null,
                owner.getId(),
                "Growth",
                null,
                new BigDecimal("99.99")));

        assertThat(service.getName()).isEqualTo("Checkout API v2");
        assertThat(service.getOwner()).isEqualTo(owner);
        assertThat(service.getTeam()).isEqualTo("Growth");
        assertThat(service.getAvailabilityTarget()).isEqualByComparingTo("99.99");

        verify(activityRecorder)
                .recordServiceFieldChange(
                        eq(service),
                        eq(admin),
                        eq(ActivityEventType.SERVICE_UPDATED),
                        eq("name"),
                        eq("Checkout API"),
                        eq("Checkout API v2"),
                        eq(null));
        verify(activityRecorder)
                .recordServiceFieldChange(
                        eq(service),
                        eq(admin),
                        eq(ActivityEventType.SERVICE_UPDATED),
                        eq("team"),
                        eq("Payments"),
                        eq("Growth"),
                        eq(null));
        // Audited by email, not opaque UUID, so history stays readable.
        verify(activityRecorder)
                .recordServiceFieldChange(
                        eq(service),
                        eq(admin),
                        eq(ActivityEventType.SERVICE_UPDATED),
                        eq("owner"),
                        eq("previous-owner@opsbridge.local"),
                        eq("owner@opsbridge.local"),
                        eq(null));
        verify(activityRecorder)
                .recordServiceFieldChange(
                        eq(service),
                        eq(admin),
                        eq(ActivityEventType.SERVICE_UPDATED),
                        eq("availabilityTarget"),
                        eq("99.90"),
                        eq("99.99"),
                        eq(null));
        // description and escalationContact were not sent, so nothing else was written.
        Mockito.verifyNoMoreInteractions(activityRecorder);
    }

    @Test
    void update_recordsNothingWhenEveryFieldIsResentUnchanged() {
        catalog.update(new UpdateServiceCommand(
                service.getId(),
                admin.getId(),
                "Checkout API",
                "Handles checkout",
                service.getOwner().getId(),
                "Payments",
                "oncall@opsbridge.local",
                new BigDecimal("99.90")));

        verifyNoInteractions(activityRecorder);
    }

    @Test
    void update_treatsATrailingZeroOnTheAvailabilityTargetAsNoChange() {
        // BigDecimal.equals treats 99.90 and 99.9000 as different, which would log a phantom edit.
        catalog.update(new UpdateServiceCommand(
                service.getId(), admin.getId(), null, null, null, null, null, new BigDecimal("99.9000")));

        verifyNoInteractions(activityRecorder);
    }

    @Test
    void update_rejectsAnUnknownService() {
        UUID missing = UUID.randomUUID();
        when(serviceRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> catalog.update(new UpdateServiceCommand(
                        missing, admin.getId(), "Anything", null, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(activityRecorder);
    }

    @Test
    void update_returnsTheServicesLiveOpenIncidentCountNotZero() {
        // Regression: update used to return the entity alone and the mapper defaulted the count to 0.
        when(incidentRepository.countOpenIncidentsByService(anyCollection(), anyCollection()))
                .thenReturn(List.of(openCount(service.getId(), Priority.P1, 2L)));

        ServiceWithOpenIncidents result = catalog.update(new UpdateServiceCommand(
                service.getId(), admin.getId(), "Renamed", null, null, null, null, null));

        assertThat(result.openIncidentCount()).isEqualTo(2L);
        assertThat(result.urgentOpenIncidentCount()).isEqualTo(2L);
    }

    @Test
    void archive_returnsTheServicesLiveOpenIncidentCountNotZero() {
        when(incidentRepository.countOpenIncidentsByService(anyCollection(), anyCollection()))
                .thenReturn(List.of(openCount(service.getId(), Priority.P3, 5L)));

        ServiceWithOpenIncidents result = catalog.archive(new ArchiveServiceCommand(service.getId(), admin.getId()));

        assertThat(result.openIncidentCount()).isEqualTo(5L);
        assertThat(result.urgentOpenIncidentCount()).isZero();
    }

    @Test
    void archive_reportsCountsEvenWhenTheServiceWasAlreadyArchived() {
        service.setStatus(ServiceStatus.ARCHIVED);
        when(incidentRepository.countOpenIncidentsByService(anyCollection(), anyCollection()))
                .thenReturn(List.of(openCount(service.getId(), Priority.P2, 1L)));

        // The early-return no-op path must not skip the counts either.
        ServiceWithOpenIncidents result = catalog.archive(new ArchiveServiceCommand(service.getId(), admin.getId()));

        assertThat(result.openIncidentCount()).isEqualTo(1L);
    }

    @Test
    void create_reportsZeroWithoutQueryingBecauseANewServiceHasNoHistory() {
        ServiceWithOpenIncidents created = catalog.create(new CreateServiceCommand(
                admin.getId(), "Payments API", null, owner.getId(), null, null, null));

        assertThat(created.openIncidentCount()).isZero();
        assertThat(created.urgentOpenIncidentCount()).isZero();
    }

    @Test
    void archive_flipsTheStatusAndAuditsIt() {
        Service archived = catalog.archive(new ArchiveServiceCommand(service.getId(), admin.getId())).service();

        assertThat(archived.getStatus()).isEqualTo(ServiceStatus.ARCHIVED);
        verify(activityRecorder)
                .recordServiceFieldChange(
                        eq(service),
                        eq(admin),
                        eq(ActivityEventType.SERVICE_ARCHIVED),
                        eq("status"),
                        eq("ACTIVE"),
                        eq("ARCHIVED"),
                        eq(null));
    }

    @Test
    void archive_isANoOpForAnAlreadyArchivedService() {
        service.setStatus(ServiceStatus.ARCHIVED);

        Service result = catalog.archive(new ArchiveServiceCommand(service.getId(), admin.getId())).service();

        assertThat(result.getStatus()).isEqualTo(ServiceStatus.ARCHIVED);
        verifyNoInteractions(activityRecorder);
        verify(serviceRepository, never()).saveAndFlush(any());
    }

    private Service storedService() {
        User previousOwner = user("previous-owner@opsbridge.local", Role.OPERATOR);
        Service stored = new Service("Checkout API", "Handles checkout", previousOwner);
        ReflectionTestUtils.setField(stored, "id", UUID.randomUUID());
        stored.setTeam("Payments");
        stored.setEscalationContact("oncall@opsbridge.local");
        stored.setAvailabilityTarget(new BigDecimal("99.90"));
        when(serviceRepository.findById(stored.getId())).thenReturn(Optional.of(stored));
        return stored;
    }

    private static User user(String email, Role role) {
        User user = new User("Test " + email, email, "hash", role);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
