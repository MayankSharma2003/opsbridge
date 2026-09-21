package io.opsbridge.api.response;

import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.domain.enums.SlaState;
import java.time.Instant;
import java.util.UUID;

// Row shape for the incident list -- lighter than the full detail payload.
public record IncidentSummaryResponse(
        UUID id,
        String publicKey,
        String title,
        ServiceSummaryResponse service,
        UserSummaryResponse assignee,
        Priority priority,
        Severity severity,
        IncidentStatus status,
        SlaState slaState,
        Instant slaResolutionTargetAt,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
