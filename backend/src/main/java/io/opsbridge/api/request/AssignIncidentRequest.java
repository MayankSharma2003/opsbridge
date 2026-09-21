package io.opsbridge.api.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** A null assigneeId unassigns the incident. */
public record AssignIncidentRequest(@NotNull Integer expectedVersion, UUID assigneeId) {
}
