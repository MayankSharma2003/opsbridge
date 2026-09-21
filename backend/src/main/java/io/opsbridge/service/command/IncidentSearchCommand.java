package io.opsbridge.service.command;

import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Severity;
import java.time.LocalDate;
import java.util.UUID;

// Optional incident-list search (spec 4.5); null/blank field means don't filter on it. from/to are
// calendar dates bounding createdAt inclusive of both ends.
// No SLA/breach filter on purpose -- that state can go stale for an untouched incident, and a
// paginated sortable list can't cheaply re-evaluate it live like the dashboard's bounded aggregate does.
public record IncidentSearchCommand(
        String q,
        IncidentStatus status,
        Priority priority,
        Severity severity,
        UUID serviceId,
        LocalDate from,
        LocalDate to) {
}
