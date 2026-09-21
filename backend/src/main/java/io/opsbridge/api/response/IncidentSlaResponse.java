package io.opsbridge.api.response;

import io.opsbridge.domain.enums.SlaState;
import java.time.Instant;

// Raw enum; frontend maps it to a localized label.
public record IncidentSlaResponse(
        SlaState state,
        Instant startedAt,
        Instant responseTargetAt,
        Instant mitigationTargetAt,
        Instant resolutionTargetAt,
        Instant respondedAt,
        Instant mitigatedAt,
        Instant resolvedAt,
        Instant breachedAt) {
}
