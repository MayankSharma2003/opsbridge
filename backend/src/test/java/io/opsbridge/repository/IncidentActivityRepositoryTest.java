package io.opsbridge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.entity.IncidentActivity;
import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.ActivityEventType;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.domain.enums.Severity;
import java.time.Instant;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

/** Backs GET /incidents/{id}/activity -- proves the paginated finder and its eager actor fetch. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class IncidentActivityRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-03-08T12:00:00Z");

    @Autowired
    private IncidentActivityRepository incidentActivityRepository;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private UserRepository userRepository;

    private Incident incident;
    private User actor;

    @BeforeEach
    void setUp() {
        actor = userRepository.save(new User("Operator", "operator@opsbridge.local", "hash", Role.OPERATOR));
        Service service = serviceRepository.save(new Service("Checkout API", null, actor));
        Incident stored =
                new Incident("INC-1000", "Checkout is down", null, service, actor, Priority.P1, Severity.SEV1);
        stored.setSlaStartedAt(NOW);
        incident = incidentRepository.save(stored);
    }

    @Test
    void findByIncidentId_onlyReturnsRowsForThatIncident() {
        record(incident, ActivityEventType.CREATED, null, "OPEN");
        Incident other = incidentRepository.save(new Incident(
                "INC-1001", "Other incident", null, incident.getService(), actor, Priority.P2, Severity.SEV3));
        record(other, ActivityEventType.CREATED, null, "OPEN");

        Page<IncidentActivity> page =
                incidentActivityRepository.findByIncidentId(incident.getId(), PageRequest.of(0, 25));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getIncident().getId()).isEqualTo(incident.getId());
    }

    @Test
    void findByIncidentId_ordersOldestFirstWhenSortedByCreatedAtAscending() {
        record(incident, ActivityEventType.CREATED, null, "OPEN");
        record(incident, ActivityEventType.STATUS_CHANGED, "OPEN", "TRIAGED");
        record(incident, ActivityEventType.STATUS_CHANGED, "TRIAGED", "INVESTIGATING");

        Page<IncidentActivity> page = incidentActivityRepository.findByIncidentId(
                incident.getId(), PageRequest.of(0, 25, Sort.by("createdAt").ascending()));

        assertThat(page.getContent())
                .extracting(IncidentActivity::getEventType)
                .containsExactly(
                        ActivityEventType.CREATED, ActivityEventType.STATUS_CHANGED, ActivityEventType.STATUS_CHANGED);
    }

    @Test
    void findByIncidentId_fetchesActorEagerly() {
        record(incident, ActivityEventType.CREATED, null, "OPEN");

        Page<IncidentActivity> page =
                incidentActivityRepository.findByIncidentId(incident.getId(), PageRequest.of(0, 25));

        assertThat(Hibernate.isInitialized(page.getContent().getFirst().getActor())).isTrue();
    }

    private void record(Incident target, ActivityEventType eventType, String from, String to) {
        incidentActivityRepository.save(
                IncidentActivity.forIncident(target, actor, eventType, null, from, to, null, NOW));
    }
}
