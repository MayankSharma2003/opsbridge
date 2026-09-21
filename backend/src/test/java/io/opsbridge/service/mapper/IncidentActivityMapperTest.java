package io.opsbridge.service.mapper;

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
import org.junit.jupiter.api.Test;

class IncidentActivityMapperTest {

    private final IncidentActivityMapper mapper = new IncidentActivityMapper(new UserMapper());

    private final User actor = new User("M. Okafor", "okafor@opsbridge.local", "hash", Role.OPERATOR);

    private IncidentActivity activity(ActivityEventType type, String field, String from, String to, String comment) {
        Service service = new Service("checkout-api", null, actor);
        Incident incident = new Incident("INC-1000", "t", null, service, actor, Priority.P1, Severity.SEV1);
        return IncidentActivity.forIncident(
                incident, actor, type, field, from, to, comment, Instant.parse("2026-03-01T09:00:00Z"));
    }

    @Test
    void toResponse_carriesTheAuditQuartetTheTimelineRendersFrom() {
        var response = mapper.toResponse(
                activity(ActivityEventType.STATUS_CHANGED, "status", "OPEN", "TRIAGED", "Confirmed"));

        assertThat(response.eventType()).isEqualTo(ActivityEventType.STATUS_CHANGED);
        assertThat(response.fieldName()).isEqualTo("status");
        assertThat(response.fromValue()).isEqualTo("OPEN");
        assertThat(response.toValue()).isEqualTo("TRIAGED");
        assertThat(response.comment()).isEqualTo("Confirmed");
        assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-03-01T09:00:00Z"));
    }

    @Test
    void toResponse_namesTheActorSoTheTrailIsAttributable() {
        var response = mapper.toResponse(activity(ActivityEventType.CREATED, null, null, null, null));

        assertThat(response.actor().name()).isEqualTo("M. Okafor");
        assertThat(response.actor().email()).isEqualTo("okafor@opsbridge.local");
    }

    @Test
    void toResponse_leavesRawEnumValuesForTheFrontendToTranslate() {
        var response = mapper.toResponse(
                activity(ActivityEventType.PRIORITY_CHANGED, "priority", "P2", "P1", null));

        // "P2"/"P1", not "High"/"Critical" -- formatActivity does the labelling client-side.
        assertThat(response.fromValue()).isEqualTo("P2");
        assertThat(response.toValue()).isEqualTo("P1");
    }

    @Test
    void toResponse_keepsNullsForAnEventWithNoFieldChange() {
        var response = mapper.toResponse(activity(ActivityEventType.CREATED, null, null, null, null));

        assertThat(response.fieldName()).isNull();
        assertThat(response.fromValue()).isNull();
        assertThat(response.comment()).isNull();
    }
}
