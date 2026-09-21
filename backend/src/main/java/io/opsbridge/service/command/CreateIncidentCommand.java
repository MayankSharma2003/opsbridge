package io.opsbridge.service.command;

import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Severity;
import java.util.UUID;

public record CreateIncidentCommand(
        String title,
        String description,
        UUID serviceId,
        UUID reporterId,
        Priority priority,
        Severity severity) {
}
