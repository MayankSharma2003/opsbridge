package io.opsbridge.api.response;

// mttrMinutes is null (not zero) when nothing resolved in the trailing 7 days -- zero would misread as "resolved instantly."
// unassignedIncidents is a subset of openIncidents, not separate: open incidents nobody has picked up.
public record DashboardKpiResponse(
        long openIncidents, long unassignedIncidents, long slaBreachesThisWeek, Double mttrMinutes) {
}
