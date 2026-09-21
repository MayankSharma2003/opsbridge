package io.opsbridge.controller;

import io.opsbridge.api.response.DashboardSummaryResponse;
import io.opsbridge.security.policy.Policies;
import io.opsbridge.service.DashboardService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Server-side dashboard aggregates (spec 4.4); open to any authenticated role.
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    @PreAuthorize(Policies.AUTHENTICATED)
    public DashboardSummaryResponse summary() {
        return dashboardService.summary();
    }
}
