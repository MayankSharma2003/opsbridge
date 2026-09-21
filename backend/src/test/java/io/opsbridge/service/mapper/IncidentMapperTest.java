package io.opsbridge.service.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.domain.enums.SlaState;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// Pins the wire contract: raw (never localised) enums, the version optimistic locking depends on,
// and a full SLA block on the detail response that the summary deliberately omits.
class IncidentMapperTest {

    private final IncidentMapper mapper = new IncidentMapper(new ServiceMapper(new UserMapper()), new UserMapper());

    private Incident incident() {
        User reporter = new User("Reporter", "reporter@opsbridge.local", "hash", Role.OPERATOR);
        Service service = new Service("checkout-api", null, reporter);
        Incident incident =
                new Incident("INC-1000", "Checkout is down", "Cards failing", service, reporter, Priority.P1, Severity.SEV1);
        incident.setStatus(IncidentStatus.INVESTIGATING);
        incident.setSlaState(SlaState.AT_RISK);
        incident.setSlaStartedAt(Instant.parse("2026-03-01T00:00:00Z"));
        incident.setSlaResolutionTargetAt(Instant.parse("2026-03-01T04:00:00Z"));
        ReflectionTestUtils.setField(incident, "version", 7);
        return incident;
    }

    @Test
    void toResponse_sendsRawEnumsRatherThanDisplayLabels() {
        var response = mapper.toResponse(incident());

        // The backend must never pre-translate; the frontend maps these to i18n labels.
        assertThat(response.priority()).isEqualTo(Priority.P1);
        assertThat(response.severity()).isEqualTo(Severity.SEV1);
        assertThat(response.status()).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(response.sla().state()).isEqualTo(SlaState.AT_RISK);
    }

    @Test
    void toResponse_carriesTheVersionTheOptimisticLockNeeds() {
        assertThat(mapper.toResponse(incident()).version()).isEqualTo(7);
    }

    @Test
    void toResponse_includesTheFullSlaBlock() {
        var sla = mapper.toResponse(incident()).sla();

        assertThat(sla.startedAt()).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(sla.resolutionTargetAt()).isEqualTo(Instant.parse("2026-03-01T04:00:00Z"));
        assertThat(sla.respondedAt()).isNull();
        assertThat(sla.breachedAt()).isNull();
    }

    @Test
    void toResponse_mapsAnUnassignedIncidentToANullAssigneeRatherThanThrowing() {
        assertThat(mapper.toResponse(incident()).assignee()).isNull();
    }

    @Test
    void toResponse_includesTheReporterAndLinkedService() {
        var response = mapper.toResponse(incident());

        assertThat(response.reporter().email()).isEqualTo("reporter@opsbridge.local");
        assertThat(response.service().name()).isEqualTo("checkout-api");
    }

    @Test
    void toSummary_carriesTheListColumnsAndFlattensSlaToStateAndTarget() {
        var summary = mapper.toSummary(incident());

        assertThat(summary.publicKey()).isEqualTo("INC-1000");
        assertThat(summary.slaState()).isEqualTo(SlaState.AT_RISK);
        assertThat(summary.slaResolutionTargetAt()).isEqualTo(Instant.parse("2026-03-01T04:00:00Z"));
        assertThat(summary.version()).isEqualTo(7);
    }

    @Test
    void toSummary_omitsTheHeavyDetailFieldsTheListDoesNotRender() {
        var summary = mapper.toSummary(incident());

        // No description/reporter/notes -- the live channel merges a summary rather than replacing the detail.
        assertThat(summary.getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("description", "reporter", "resolutionNotes");
    }
}
