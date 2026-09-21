package io.opsbridge.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.domain.enums.ServiceStatus;
import io.opsbridge.service.command.ServiceSearchCommand;
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

// Proves ServiceSpecifications against a real database: filters combine with AND, q searches
// name/description/team, and owner comes back eager -- these queries bypass the @EntityGraph on
// findById, so this is what stands between open-in-view:false and a LazyInitializationException.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ServiceRepositoryTest {

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private UserRepository userRepository;

    private User ownerA;
    private User ownerB;

    @BeforeEach
    void setUp() {
        ownerA = userRepository.save(new User("Owner A", "owner-a@opsbridge.local", "hash", Role.ADMIN));
        ownerB = userRepository.save(new User("Owner B", "owner-b@opsbridge.local", "hash", Role.ADMIN));

        save("Checkout API", "Handles checkout flow", "Payments", ServiceStatus.ACTIVE, ownerA);
        save("Payments Gateway", "Card processing", "Payments", ServiceStatus.ARCHIVED, ownerA);
        save("Notification Service", "Sends checkout confirmation emails", "Growth", ServiceStatus.ACTIVE, ownerB);
        save("Search Index", null, "Platform", ServiceStatus.ACTIVE, ownerB);
    }

    @Test
    void matching_filtersByStatus() {
        Page<Service> result = search(new ServiceSearchCommand(null, ServiceStatus.ARCHIVED, null));

        assertThat(result.getContent()).extracting(Service::getName).containsExactly("Payments Gateway");
    }

    @Test
    void matching_filtersByTeamCaseInsensitively() {
        Page<Service> result = search(new ServiceSearchCommand(null, null, "payments"));

        assertThat(result.getContent())
                .extracting(Service::getName)
                .containsExactlyInAnyOrder("Checkout API", "Payments Gateway");
    }

    @Test
    void matching_searchesKeywordAcrossNameAndDescription() {
        Page<Service> result = search(new ServiceSearchCommand("checkout", null, null));

        // Matches on name ("Checkout API") and on description ("...checkout confirmation...").
        assertThat(result.getContent())
                .extracting(Service::getName)
                .containsExactlyInAnyOrder("Checkout API", "Notification Service");
    }

    @Test
    void matching_combinesStatusAndTeamWithAnd() {
        Page<Service> result = search(new ServiceSearchCommand(null, ServiceStatus.ACTIVE, "Payments"));

        // Payments Gateway matches the team, not the status -- AND excludes it.
        assertThat(result.getContent()).extracting(Service::getName).containsExactly("Checkout API");
    }

    @Test
    void matching_treatsABlankKeywordAsNoFilter() {
        Page<Service> result = search(new ServiceSearchCommand("   ", null, null));

        assertThat(result.getTotalElements()).isEqualTo(4);
    }

    @Test
    void matching_fetchesOwnerEagerlySoItIsNotALazyProxy() {
        Page<Service> result = search(new ServiceSearchCommand(null, ServiceStatus.ACTIVE, null));

        assertThat(result.getContent()).isNotEmpty();
        result.getContent().forEach(service -> assertThat(Hibernate.isInitialized(service.getOwner())).isTrue());
    }

    @Test
    void matching_paginatesTheUnfilteredResult() {
        Page<Service> firstPage = serviceRepository.findAll(
                ServiceSpecifications.matching(new ServiceSearchCommand(null, null, null)),
                PageRequest.of(0, 2, Sort.by("name")));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(4);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        // Ascending by name, so these two come before the rest.
        assertThat(firstPage.getContent()).extracting(Service::getName)
                .containsExactly("Checkout API", "Notification Service");
    }

    private Page<Service> search(ServiceSearchCommand command) {
        return serviceRepository.findAll(ServiceSpecifications.matching(command), PageRequest.of(0, 25));
    }

    private void save(String name, String description, String team, ServiceStatus status, User owner) {
        Service service = new Service(name, description, owner);
        service.setTeam(team);
        service.setStatus(status);
        serviceRepository.save(service);
    }
}
