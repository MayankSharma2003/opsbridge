package io.opsbridge.service.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.domain.enums.ServiceStatus;
import io.opsbridge.service.ServiceWithOpenIncidents;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ServiceMapperTest {

    private final ServiceMapper mapper = new ServiceMapper(new UserMapper());

    private Service service() {
        User owner = new User("Owner", "owner@opsbridge.local", "hash", Role.ADMIN);
        Service service = new Service("checkout-api", "Takes money", owner);
        service.setTeam("Payments");
        service.setEscalationContact("payments-oncall@opsbridge.local");
        service.setAvailabilityTarget(new BigDecimal("99.95"));
        return service;
    }

    @Test
    void toResponse_carriesTheOpenIncidentCountsFromThePair() {
        var response = mapper.toResponse(new ServiceWithOpenIncidents(service(), 4L, 2L));

        assertThat(response.openIncidentCount()).isEqualTo(4L);
        assertThat(response.urgentOpenIncidentCount()).isEqualTo(2L);
    }

    @Test
    void toResponse_reportsZeroCountsWithoutInventingThem() {
        var response = mapper.toResponse(new ServiceWithOpenIncidents(service(), 0L, 0L));

        assertThat(response.openIncidentCount()).isZero();
        assertThat(response.urgentOpenIncidentCount()).isZero();
    }

    @Test
    void toResponse_copiesEveryCatalogField() {
        var response = mapper.toResponse(new ServiceWithOpenIncidents(service(), 1L, 0L));

        assertThat(response.name()).isEqualTo("checkout-api");
        assertThat(response.description()).isEqualTo("Takes money");
        assertThat(response.team()).isEqualTo("Payments");
        assertThat(response.escalationContact()).isEqualTo("payments-oncall@opsbridge.local");
        assertThat(response.availabilityTarget()).isEqualByComparingTo("99.95");
        assertThat(response.status()).isEqualTo(ServiceStatus.ACTIVE);
        assertThat(response.owner().email()).isEqualTo("owner@opsbridge.local");
    }

    @Test
    void toResponse_toleratesAServiceWithNoOptionalFieldsSet() {
        User owner = new User("Owner", "owner@opsbridge.local", "hash", Role.ADMIN);
        var response = mapper.toResponse(new ServiceWithOpenIncidents(new Service("bare", null, owner), 0L, 0L));

        assertThat(response.description()).isNull();
        assertThat(response.team()).isNull();
        assertThat(response.availabilityTarget()).isNull();
    }

    @Test
    void toSummary_reducesToJustIdAndName() {
        var summary = mapper.toSummary(service());

        assertThat(summary.name()).isEqualTo("checkout-api");
    }

    @Test
    void toSummary_returnsNullForNullRatherThanThrowing() {
        // The summary path is shared and must not assume a service is always present.
        assertThat(mapper.toSummary(null)).isNull();
    }
}
