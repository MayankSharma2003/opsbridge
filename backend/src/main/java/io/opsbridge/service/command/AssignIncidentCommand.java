package io.opsbridge.service.command;

import java.util.UUID;

/** A null {@code assigneeId} unassigns the incident. */
public record AssignIncidentCommand(UUID incidentId, int expectedVersion, UUID actorId, UUID assigneeId) {
}
