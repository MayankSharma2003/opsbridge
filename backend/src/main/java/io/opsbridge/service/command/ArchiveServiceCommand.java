package io.opsbridge.service.command;

import java.util.UUID;

public record ArchiveServiceCommand(UUID serviceId, UUID actorId) {
}
