package io.opsbridge.api.response;

import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Severity;
import java.time.Instant;
import java.util.UUID;

// Enums sent raw for the frontend to localize; version must be echoed back as expectedVersion for optimistic locking.
public record IncidentResponse(
        UUID id,
        String publicKey,
        String title,
        String description,
        ServiceSummaryResponse service,
        UserSummaryResponse reporter,
        UserSummaryResponse assignee,
        Priority priority,
        Severity severity,
        IncidentStatus status,
        IncidentSlaResponse sla,
        String resolutionNotes,
        int version,
        Instant createdAt,
        Instant updatedAt) {
}
