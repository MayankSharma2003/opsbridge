package io.opsbridge.api.request;

import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Severity;
import jakarta.validation.constraints.NotNull;

/** Null priority or severity leaves that dimension unchanged. */
public record EscalateIncidentRequest(
        @NotNull Integer expectedVersion, Priority priority, Severity severity, String reason) {
}
