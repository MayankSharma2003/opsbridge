package io.opsbridge.api.response;

import java.util.List;

// priorityDistribution is zero-filled, one entry per Priority in P1..P4 order -- no special-casing in the chart.
// mttrTrend always has 7 entries, oldest first, ending today (UTC).
public record DashboardSummaryResponse(
        DashboardKpiResponse kpis,
        List<PriorityCountResponse> priorityDistribution,
        List<MttrTrendPointResponse> mttrTrend) {
}
