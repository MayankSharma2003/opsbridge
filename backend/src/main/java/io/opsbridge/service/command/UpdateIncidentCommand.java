package io.opsbridge.service.command;

import java.util.UUID;

// Null fields unchanged. serviceId relinks to another service; priority/severity go through
// EscalateIncidentCommand instead since those carry SLA consequences and their own audit event.
public record UpdateIncidentCommand(
        UUID incidentId,
        int expectedVersion,
        UUID actorId,
        String title,
        String description,
        UUID serviceId,
        String resolutionNotes) {
}
