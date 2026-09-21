package io.opsbridge.api.response;

import java.time.LocalDate;

// Null (not zero) for a day with no resolutions -- mirrors DashboardKpiResponse.
public record MttrTrendPointResponse(LocalDate date, Double mttrMinutes) {
}
