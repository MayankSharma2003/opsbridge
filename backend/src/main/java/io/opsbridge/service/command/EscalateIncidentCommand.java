package io.opsbridge.service.command;

import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Severity;
import java.util.UUID;

/** Null priority or severity leaves that dimension unchanged. */
public record EscalateIncidentCommand(
        UUID incidentId,
        int expectedVersion,
        UUID actorId,
        Priority priority,
        Severity severity,
        String reason) {
}
