package io.opsbridge.api.request;

import io.opsbridge.domain.enums.IncidentStatus;
import jakarta.validation.constraints.NotNull;

public record TransitionIncidentRequest(
        @NotNull Integer expectedVersion,
        @NotNull IncidentStatus targetStatus,
        String comment,
        String resolutionNotes) {
}
