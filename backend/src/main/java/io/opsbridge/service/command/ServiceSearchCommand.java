package io.opsbridge.service.command;

import io.opsbridge.domain.enums.ServiceStatus;

// Optional catalog search (spec 4.3, 4.5); null/blank field means don't filter on it.
public record ServiceSearchCommand(String q, ServiceStatus status, String team) {
}
