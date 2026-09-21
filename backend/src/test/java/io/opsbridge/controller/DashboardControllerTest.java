package io.opsbridge.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opsbridge.api.response.DashboardKpiResponse;
import io.opsbridge.api.response.DashboardSummaryResponse;
import io.opsbridge.api.response.MttrTrendPointResponse;
import io.opsbridge.api.response.PriorityCountResponse;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.security.RestSecurityResponses;
import io.opsbridge.security.SecurityConfig;
import io.opsbridge.security.jwt.JwtAuthFilter;
import io.opsbridge.security.jwt.JwtProperties;
import io.opsbridge.security.jwt.JwtTokenProvider;
import io.opsbridge.service.DashboardService;
import io.opsbridge.support.clock.ClockConfig;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DashboardController.class)
@Import({SecurityConfig.class, RestSecurityResponses.class, ClockConfig.class, JwtAuthFilter.class, JwtTokenProvider.class})
@EnableConfigurationProperties(JwtProperties.class)
@ActiveProfiles("test")
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        DashboardSummaryResponse summary = new DashboardSummaryResponse(
                new DashboardKpiResponse(4L, 2L, 1L, 37.5),
                List.of(new PriorityCountResponse(Priority.P1, 2L), new PriorityCountResponse(Priority.P2, 0L)),
                List.of(new MttrTrendPointResponse(LocalDate.of(2026, 3, 8), null)));
        when(dashboardService.summary()).thenReturn(summary);
    }

    @Test
    void summary_returns401WithoutAToken() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHENTICATED")));
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void summary_returns200ForEveryRole(Role role) throws Exception {
        String token = jwtTokenProvider
                .issueToken(UUID.randomUUID(), "user@opsbridge.local", "Dev User", role)
                .token();

        mockMvc.perform(get("/api/v1/dashboard/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.openIncidents", is(4)))
                .andExpect(jsonPath("$.kpis.unassignedIncidents", is(2)))
                .andExpect(jsonPath("$.kpis.slaBreachesThisWeek", is(1)))
                .andExpect(jsonPath("$.kpis.mttrMinutes", is(37.5)))
                // Raw enum value; the frontend localizes the label.
                .andExpect(jsonPath("$.priorityDistribution[0].priority", is("P1")))
                .andExpect(jsonPath("$.mttrTrend[0].date", is("2026-03-08")))
                .andExpect(jsonPath("$.mttrTrend[0].mttrMinutes").doesNotExist());
    }
}
