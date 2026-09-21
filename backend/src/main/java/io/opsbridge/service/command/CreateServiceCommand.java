package io.opsbridge.service.command;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateServiceCommand(
        UUID actorId,
        String name,
        String description,
        UUID ownerId,
        String team,
        String escalationContact,
        BigDecimal availabilityTarget) {
}
