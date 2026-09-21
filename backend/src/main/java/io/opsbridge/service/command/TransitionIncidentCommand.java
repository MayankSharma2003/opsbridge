package io.opsbridge.service.command;

import io.opsbridge.domain.enums.IncidentStatus;
import java.util.UUID;

/** {@code resolutionNotes} is optional and only applied when moving to RESOLVED. */
public record TransitionIncidentCommand(
        UUID incidentId,
        int expectedVersion,
        UUID actorId,
        IncidentStatus targetStatus,
        String comment,
        String resolutionNotes) {
}
