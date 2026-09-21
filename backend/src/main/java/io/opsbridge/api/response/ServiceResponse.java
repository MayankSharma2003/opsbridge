package io.opsbridge.api.response;

import io.opsbridge.domain.enums.ServiceStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ServiceResponse(
        UUID id,
        String name,
        String description,
        UserSummaryResponse owner,
        String team,
        String escalationContact,
        BigDecimal availabilityTarget,
        ServiceStatus status,
        long openIncidentCount,
        long urgentOpenIncidentCount,
        Instant createdAt,
        Instant updatedAt) {
}
