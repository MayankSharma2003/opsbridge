package io.opsbridge.service.command;

import java.math.BigDecimal;
import java.util.UUID;

/** Null fields are left unchanged. */
public record UpdateServiceCommand(
        UUID serviceId,
        UUID actorId,
        String name,
        String description,
        UUID ownerId,
        String team,
        String escalationContact,
        BigDecimal availabilityTarget) {
}
