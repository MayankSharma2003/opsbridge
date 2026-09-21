package io.opsbridge.support.realtime;

import io.opsbridge.api.response.IncidentSummaryResponse;
import java.time.Instant;

// type is a raw enum name -- backend never pre-localizes what the frontend displays.
// incident reuses IncidentSummaryResponse, the same shape GET /api/v1/incidents already returns --
// the socket must never leak more than a plain read could.
public record LiveEvent(LiveEventType type, Instant occurredAt, IncidentSummaryResponse incident) {

    public static LiveEvent incidentUpdated(IncidentSummaryResponse incident, Instant occurredAt) {
        return new LiveEvent(LiveEventType.INCIDENT_UPDATED, occurredAt, incident);
    }

    public static LiveEvent slaBreached(IncidentSummaryResponse incident, Instant occurredAt) {
        return new LiveEvent(LiveEventType.SLA_BREACHED, occurredAt, incident);
    }
}
