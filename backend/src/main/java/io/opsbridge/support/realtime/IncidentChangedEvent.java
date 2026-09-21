package io.opsbridge.support.realtime;

import java.util.UUID;

// Carries only the id -- listener re-reads after commit, so what goes out is committed state, not
// a snapshot that might still roll back.
public record IncidentChangedEvent(UUID incidentId) {
}
