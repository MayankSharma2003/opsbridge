package io.opsbridge.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.repository.IncidentRepository.PriorityCount;
import io.opsbridge.repository.IncidentRepository.ResolutionSample;
import io.opsbridge.repository.IncidentRepository.ServiceOpenCount;
import io.opsbridge.service.command.IncidentSearchCommand;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

// Proves the custom @Query JPQL and projection bindings DashboardService depends on, and the
// IncidentSpecifications filters IncidentService.list() builds -- a mocked service test can't cover either.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class IncidentRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-03-08T12:00:00Z");
    private static final Instant WINDOW_START = NOW.minus(Duration.ofDays(7));

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private UserRepository userRepository;

    private Service service;
    private User user;
    private int sequence = 0;

    @BeforeEach
    void setUp() {
        user = userRepository.save(new User("Reporter", "reporter@opsbridge.local", "hash", Role.OPERATOR));
        service = serviceRepository.save(new Service("Checkout API", null, user));
    }

    @Test
    void countByStatusNotIn_excludesTerminalStatuses() {
        incident(IncidentStatus.OPEN, Priority.P1);
        incident(IncidentStatus.TRIAGED, Priority.P2);
        incident(IncidentStatus.RESOLVED, Priority.P1);
        incident(IncidentStatus.CANCELLED, Priority.P1);

        long count = incidentRepository.countByStatusNotIn(Set.of(IncidentStatus.RESOLVED, IncidentStatus.CANCELLED));

        assertThat(count).isEqualTo(2);
    }

    @Test
    void countOpenIncidentsByPriority_groupsOnlyOpenIncidents() {
        incident(IncidentStatus.OPEN, Priority.P1);
        incident(IncidentStatus.OPEN, Priority.P1);
        incident(IncidentStatus.TRIAGED, Priority.P2);
        // A resolved P1 must not inflate the P1 count.
        incident(IncidentStatus.RESOLVED, Priority.P1);

        List<PriorityCount> counts = incidentRepository.countOpenIncidentsByPriority(
                Set.of(IncidentStatus.RESOLVED, IncidentStatus.CANCELLED));

        assertThat(counts)
                .extracting(PriorityCount::getPriority, PriorityCount::getCount)
                .containsExactlyInAnyOrder(tuple(Priority.P1, 2L), tuple(Priority.P2, 1L));
    }

    @Test
    void countByAssigneeIsNullAndStatusNotIn_countsOnlyUnownedOpenWork() {
        incident(IncidentStatus.OPEN, Priority.P1);
        Incident assigned = incident(IncidentStatus.TRIAGED, Priority.P2);
        assigned.setAssignee(user);
        incidentRepository.save(assigned);
        // Terminal incidents are out of scope even with nobody assigned.
        incident(IncidentStatus.RESOLVED, Priority.P1);

        long count = incidentRepository.countByAssigneeIsNullAndStatusNotIn(
                Set.of(IncidentStatus.RESOLVED, IncidentStatus.CANCELLED));

        assertThat(count).isEqualTo(1);
    }

    @Test
    void countOpenIncidentsByService_groupsOnlyOpenIncidentsForTheRequestedServices() {
        Service other = serviceRepository.save(new Service("identity-svc", null, user));
        incident(IncidentStatus.OPEN, Priority.P1);
        incident(IncidentStatus.INVESTIGATING, Priority.P2);
        // Terminal ones must not inflate the catalog's health column.
        incident(IncidentStatus.RESOLVED, Priority.P1);
        incident(IncidentStatus.CANCELLED, Priority.P1);
        incidentFor(other, IncidentStatus.MITIGATED);

        List<ServiceOpenCount> counts = incidentRepository.countOpenIncidentsByService(
                List.of(service.getId(), other.getId()), Set.of(IncidentStatus.RESOLVED, IncidentStatus.CANCELLED));

        // One row per (service, priority); the service layer folds these into totals.
        assertThat(counts)
                .extracting(ServiceOpenCount::getServiceId, ServiceOpenCount::getPriority, ServiceOpenCount::getCount)
                .containsExactlyInAnyOrder(
                        tuple(service.getId(), Priority.P1, 1L),
                        tuple(service.getId(), Priority.P2, 1L),
                        tuple(other.getId(), Priority.P3, 1L));
    }

    @Test
    void countOpenIncidentsByService_omitsAServiceWithNothingOpen() {
        Service quiet = serviceRepository.save(new Service("quiet-svc", null, user));
        incidentFor(quiet, IncidentStatus.RESOLVED);

        List<ServiceOpenCount> counts = incidentRepository.countOpenIncidentsByService(
                List.of(quiet.getId()), Set.of(IncidentStatus.RESOLVED, IncidentStatus.CANCELLED));

        // No row, not a zero -- ServiceCatalogService does the zero-filling.
        assertThat(counts).isEmpty();
    }

    @Test
    void findSlaBreachEvaluationCandidates_includesRecentKnownBreachesAndAllOpenIncidents() {
        Incident recentBreach = incident(IncidentStatus.RESOLVED, Priority.P1);
        recentBreach.setSlaBreachedAt(NOW.minus(Duration.ofDays(2)));
        incidentRepository.save(recentBreach);

        Incident staleBreach = incident(IncidentStatus.RESOLVED, Priority.P1);
        staleBreach.setSlaBreachedAt(NOW.minus(Duration.ofDays(30)));
        incidentRepository.save(staleBreach);

        Incident cleanResolved = incident(IncidentStatus.RESOLVED, Priority.P1);
        // sla_breached_at stays null.
        incidentRepository.save(cleanResolved);

        // Open incidents are always candidates since their persisted SLA state can be stale.
        Incident stillOpen = incident(IncidentStatus.INVESTIGATING, Priority.P1);

        List<Incident> candidates = incidentRepository.findSlaBreachEvaluationCandidates(
                WINDOW_START, Set.of(IncidentStatus.RESOLVED, IncidentStatus.CANCELLED));

        assertThat(candidates).extracting(Incident::getId)
                .containsExactlyInAnyOrder(recentBreach.getId(), stillOpen.getId());
    }

    @Test
    void findResolutionSamplesSince_onlyReturnsResolutionsInsideTheWindow() {
        Incident recent = incident(IncidentStatus.RESOLVED, Priority.P1);
        recent.setSlaStartedAt(NOW.minus(Duration.ofDays(3)).minus(Duration.ofHours(2)));
        recent.setSlaResolvedAt(NOW.minus(Duration.ofDays(3)));
        incidentRepository.save(recent);

        Incident tooOld = incident(IncidentStatus.RESOLVED, Priority.P1);
        tooOld.setSlaStartedAt(NOW.minus(Duration.ofDays(20)).minus(Duration.ofHours(1)));
        tooOld.setSlaResolvedAt(NOW.minus(Duration.ofDays(20)));
        incidentRepository.save(tooOld);

        Incident stillOpen = incident(IncidentStatus.INVESTIGATING, Priority.P1);
        // No slaResolvedAt at all -- must not appear.
        assertThat(stillOpen.getSlaResolvedAt()).isNull();

        List<ResolutionSample> samples = incidentRepository.findResolutionSamplesSince(WINDOW_START);

        assertThat(samples).hasSize(1);
        assertThat(samples.getFirst().getSlaResolvedAt()).isEqualTo(recent.getSlaResolvedAt());
        assertThat(samples.getFirst().getSlaStartedAt()).isEqualTo(recent.getSlaStartedAt());
    }

    @Test
    void matching_searchesKeywordAcrossTitleDescriptionAndPublicKey() {
        Incident checkout = incidentWithTitle("Checkout latency spike", "p99 above 3s");
        Incident login = incidentWithTitle("Login page slow", "checkout-adjacent auth path");
        incidentWithTitle("Unrelated report bug", "nothing to do with either");

        Page<Incident> result = search(new IncidentSearchCommand("checkout", null, null, null, null, null, null));

        assertThat(result.getContent()).extracting(Incident::getId)
                .containsExactlyInAnyOrder(checkout.getId(), login.getId());
    }

    @Test
    void matching_filtersByStatusPriorityAndSeverityTogetherWithAnd() {
        Incident matches = incidentWithTitle("A", null);
        matches.setStatus(IncidentStatus.INVESTIGATING);
        matches.setPriority(Priority.P1);
        matches.setSeverity(Severity.SEV1);
        incidentRepository.save(matches);

        Incident wrongStatus = incidentWithTitle("B", null);
        wrongStatus.setPriority(Priority.P1);
        wrongStatus.setSeverity(Severity.SEV1);
        incidentRepository.save(wrongStatus);

        Page<Incident> result = search(new IncidentSearchCommand(
                null, IncidentStatus.INVESTIGATING, Priority.P1, Severity.SEV1, null, null, null));

        assertThat(result.getContent()).extracting(Incident::getId).containsExactly(matches.getId());
    }

    @Test
    void matching_filtersByServiceId() {
        Service otherService = serviceRepository.save(new Service("Payments API", null, user));
        // Control: same title pool, different service -- shouldn't appear in the scoped result.
        incidentWithTitle("On checkout", null);
        Incident onPayments =
                new Incident(publicKey(), "On payments", null, otherService, user, Priority.P2, Severity.SEV2);
        onPayments.setSlaStartedAt(NOW.minus(Duration.ofHours(1)));
        incidentRepository.save(onPayments);

        Page<Incident> result =
                search(new IncidentSearchCommand(null, null, null, null, otherService.getId(), null, null));

        assertThat(result.getContent()).extracting(Incident::getId).containsExactly(onPayments.getId());
    }

    @Test
    void matching_boundsCreatedAtInclusiveOfBothEndpointDays() {
        Incident inRange = incidentWithTitle("In range", null);
        Incident tooEarly = incidentWithTitle("Too early", null);
        Incident tooLate = incidentWithTitle("Too late", null);

        // created_at is updatable=false, so a setter write is silently dropped -- only direct SQL can move it.
        backdate(inRange, Instant.parse("2026-03-05T10:00:00Z"));
        backdate(tooEarly, Instant.parse("2026-03-01T23:59:59Z"));
        backdate(tooLate, Instant.parse("2026-03-11T00:00:01Z"));
        entityManager.clear();

        Page<Incident> result = search(new IncidentSearchCommand(
                null, null, null, null, null, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 10)));

        assertThat(result.getContent()).extracting(Incident::getId).containsExactly(inRange.getId());
    }

    private void backdate(Incident incident, Instant createdAt) {
        entityManager
                .getEntityManager()
                .createQuery("UPDATE Incident i SET i.createdAt = :createdAt WHERE i.id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", incident.getId())
                .executeUpdate();
    }

    @Test
    void matching_fetchesAssociationsEagerlySoTheyAreNotLazyProxies() {
        incidentWithTitle("Needs owner data", null);

        Page<Incident> result = search(new IncidentSearchCommand(null, null, null, null, null, null, null));

        assertThat(result.getContent()).isNotEmpty();
        result.getContent().forEach(incident -> {
            assertThat(Hibernate.isInitialized(incident.getService())).isTrue();
            assertThat(Hibernate.isInitialized(incident.getReporter())).isTrue();
        });
    }

    private Page<Incident> search(IncidentSearchCommand command) {
        return incidentRepository.findAll(IncidentSpecifications.matching(command), PageRequest.of(0, 25));
    }

    private Incident incidentWithTitle(String title, String description) {
        Incident incident = new Incident(publicKey(), title, description, service, user, Priority.P2, Severity.SEV3);
        incident.setSlaStartedAt(NOW.minus(Duration.ofHours(1)));
        return incidentRepository.save(incident);
    }

    private Incident incident(IncidentStatus status, Priority priority) {
        Incident incident =
                new Incident(publicKey(), "Incident", null, service, user, priority, Severity.SEV2);
        incident.setStatus(status);
        incident.setSlaStartedAt(NOW.minus(Duration.ofHours(1)));
        return incidentRepository.save(incident);
    }

    private Incident incidentFor(Service target, IncidentStatus status) {
        Incident incident =
                new Incident(publicKey(), "Incident", null, target, user, Priority.P3, Severity.SEV3);
        incident.setStatus(status);
        incident.setSlaStartedAt(NOW.minus(Duration.ofHours(1)));
        return incidentRepository.save(incident);
    }

    private String publicKey() {
        return "INC-" + (++sequence);
    }
}
