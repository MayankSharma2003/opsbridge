package io.opsbridge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opsbridge.api.response.DashboardSummaryResponse;
import io.opsbridge.api.response.MttrTrendPointResponse;
import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Role;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.domain.enums.SlaState;
import io.opsbridge.repository.IncidentRepository;
import io.opsbridge.repository.IncidentRepository.PriorityCount;
import io.opsbridge.repository.IncidentRepository.ResolutionSample;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DashboardServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-08T12:00:00Z");
    private static final Instant WINDOW_START = NOW.minus(Duration.ofDays(7));
    private static final Set<IncidentStatus> TERMINAL = Set.of(IncidentStatus.RESOLVED, IncidentStatus.CANCELLED);

    private IncidentRepository incidentRepository;
    private DashboardService dashboardService;

    private User user;
    private Service service;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(IncidentRepository.class);
        dashboardService = new DashboardService(
                incidentRepository, new IncidentStateMachine(), new SlaCalculator(), Clock.fixed(NOW, ZoneOffset.UTC));

        user = user();
        service = service();

        when(incidentRepository.countByStatusNotIn(any())).thenReturn(0L);
        when(incidentRepository.countByAssigneeIsNullAndStatusNotIn(any())).thenReturn(0L);
        when(incidentRepository.countOpenIncidentsByPriority(any())).thenReturn(List.of());
        when(incidentRepository.findSlaBreachEvaluationCandidates(any(), any())).thenReturn(List.of());
        when(incidentRepository.findResolutionSamplesSince(any())).thenReturn(List.of());
    }

    @Test
    void summary_reportsOpenIncidentCountAndDerivesTheTerminalSetFromTheStateMachine() {
        when(incidentRepository.countByStatusNotIn(eq(TERMINAL))).thenReturn(7L);

        DashboardSummaryResponse result = dashboardService.summary();

        assertThat(result.kpis().openIncidents()).isEqualTo(7L);
    }

    @Test
    void summary_reportsTheUnassignedSliceOfTheOpenCount() {
        when(incidentRepository.countByStatusNotIn(eq(TERMINAL))).thenReturn(7L);
        when(incidentRepository.countByAssigneeIsNullAndStatusNotIn(eq(TERMINAL))).thenReturn(3L);

        DashboardSummaryResponse result = dashboardService.summary();

        // A subset of the open count, not a separate population.
        assertThat(result.kpis().openIncidents()).isEqualTo(7L);
        assertThat(result.kpis().unassignedIncidents()).isEqualTo(3L);
    }

    @Test
    void summary_reportsZeroUnassignedWhenEveryOpenIncidentHasAnOwner() {
        when(incidentRepository.countByStatusNotIn(eq(TERMINAL))).thenReturn(5L);
        when(incidentRepository.countByAssigneeIsNullAndStatusNotIn(eq(TERMINAL))).thenReturn(0L);

        DashboardSummaryResponse result = dashboardService.summary();

        assertThat(result.kpis().unassignedIncidents()).isZero();
    }

    @Test
    void summary_zeroFillsPriorityDistributionAndPreservesP1ToP4Order() {
        when(incidentRepository.countOpenIncidentsByPriority(eq(TERMINAL)))
                .thenReturn(List.of(priorityCount(Priority.P1, 3), priorityCount(Priority.P3, 1)));

        DashboardSummaryResponse result = dashboardService.summary();

        assertThat(result.priorityDistribution())
                .extracting("priority", "count")
                .containsExactly(
                        tuple(Priority.P1, 3L), tuple(Priority.P2, 0L), tuple(Priority.P3, 1L), tuple(Priority.P4, 0L));
    }

    @Test
    void summary_countsALiveBreachOnAnOpenIncidentDespiteAStalePersistedState() {
        // Persisted state still says ON_TRACK after the deadline silently passed; live evaluation must catch it anyway.
        Incident staleOpen = incident(IncidentStatus.OPEN);
        staleOpen.setSlaResponseTargetAt(NOW.minus(Duration.ofHours(1)));
        staleOpen.setSlaState(SlaState.ON_TRACK);
        staleOpen.setSlaBreachedAt(null);
        when(incidentRepository.findSlaBreachEvaluationCandidates(eq(WINDOW_START), eq(TERMINAL)))
                .thenReturn(List.of(staleOpen));

        DashboardSummaryResponse result = dashboardService.summary();

        assertThat(result.kpis().slaBreachesThisWeek()).isEqualTo(1L);
    }

    @Test
    void summary_excludesALiveBreachOlderThanTheSevenDayWindow() {
        Incident longOpen = incident(IncidentStatus.OPEN);
        longOpen.setSlaResponseTargetAt(NOW.minus(Duration.ofDays(30)));
        when(incidentRepository.findSlaBreachEvaluationCandidates(eq(WINDOW_START), eq(TERMINAL)))
                .thenReturn(List.of(longOpen));

        DashboardSummaryResponse result = dashboardService.summary();

        assertThat(result.kpis().slaBreachesThisWeek()).isZero();
    }

    @Test
    void summary_countsAResolvedIncidentThatWasResolvedLate() {
        Incident resolvedLate = incident(IncidentStatus.RESOLVED);
        resolvedLate.setSlaResolutionTargetAt(NOW.minus(Duration.ofDays(3)).minus(Duration.ofMinutes(10)));
        resolvedLate.setSlaResolvedAt(NOW.minus(Duration.ofDays(3)));
        when(incidentRepository.findSlaBreachEvaluationCandidates(eq(WINDOW_START), eq(TERMINAL)))
                .thenReturn(List.of(resolvedLate));

        DashboardSummaryResponse result = dashboardService.summary();

        assertThat(result.kpis().slaBreachesThisWeek()).isEqualTo(1L);
    }

    @Test
    void summary_reportsNullMttrWhenNoIncidentsResolvedInTheWindow() {
        DashboardSummaryResponse result = dashboardService.summary();

        assertThat(result.kpis().mttrMinutes()).isNull();
        assertThat(result.mttrTrend()).allMatch(point -> point.mttrMinutes() == null);
    }

    @Test
    void summary_computesMttrAsTheAverageAcrossAllSamplesInTheWindow() {
        Instant resolvedAt = NOW.minus(Duration.ofDays(2));
        when(incidentRepository.findResolutionSamplesSince(eq(WINDOW_START)))
                .thenReturn(List.of(
                        resolutionSample(resolvedAt.minus(Duration.ofMinutes(30)), resolvedAt),
                        resolutionSample(resolvedAt.minus(Duration.ofMinutes(90)), resolvedAt)));

        DashboardSummaryResponse result = dashboardService.summary();

        assertThat(result.kpis().mttrMinutes()).isEqualTo(60.0);
    }

    @Test
    void summary_bucketsTheTrendByUtcDayOldestFirstOverSevenPoints() {
        Instant threeDaysAgo = NOW.minus(Duration.ofDays(3));
        when(incidentRepository.findResolutionSamplesSince(eq(WINDOW_START)))
                .thenReturn(List.of(resolutionSample(threeDaysAgo.minus(Duration.ofMinutes(45)), threeDaysAgo)));

        DashboardSummaryResponse result = dashboardService.summary();

        assertThat(result.mttrTrend()).hasSize(7);
        assertThat(result.mttrTrend().get(0).date()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(result.mttrTrend().get(6).date()).isEqualTo(LocalDate.of(2026, 3, 8));
        LocalDate expectedDay = threeDaysAgo.atZone(ZoneOffset.UTC).toLocalDate();
        assertThat(result.mttrTrend())
                .filteredOn(point -> point.date().equals(expectedDay))
                .extracting(MttrTrendPointResponse::mttrMinutes)
                .containsExactly(45.0);
        assertThat(result.mttrTrend())
                .filteredOn(point -> !point.date().equals(expectedDay))
                .allMatch(point -> point.mttrMinutes() == null);
    }

    private PriorityCount priorityCount(Priority priority, long count) {
        return new PriorityCount() {
            @Override
            public Priority getPriority() {
                return priority;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    private ResolutionSample resolutionSample(Instant startedAt, Instant resolvedAt) {
        return new ResolutionSample() {
            @Override
            public Instant getSlaStartedAt() {
                return startedAt;
            }

            @Override
            public Instant getSlaResolvedAt() {
                return resolvedAt;
            }
        };
    }

    private Incident incident(IncidentStatus status) {
        Incident incident =
                new Incident("INC-1000", "Incident", null, service, user, Priority.P2, Severity.SEV3);
        ReflectionTestUtils.setField(incident, "id", UUID.randomUUID());
        incident.setStatus(status);
        incident.setSlaStartedAt(NOW.minus(Duration.ofDays(1)));
        return incident;
    }

    private static User user() {
        User user = new User("Reporter", "reporter@opsbridge.local", "hash", Role.OPERATOR);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private static Service service() {
        Service service = new Service("Checkout API", null, null);
        ReflectionTestUtils.setField(service, "id", UUID.randomUUID());
        return service;
    }
}
