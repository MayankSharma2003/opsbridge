package io.opsbridge.service;

import io.opsbridge.api.response.DashboardKpiResponse;
import io.opsbridge.api.response.DashboardSummaryResponse;
import io.opsbridge.api.response.MttrTrendPointResponse;
import io.opsbridge.api.response.PriorityCountResponse;
import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.repository.IncidentRepository;
import io.opsbridge.repository.IncidentRepository.PriorityCount;
import io.opsbridge.repository.IncidentRepository.ResolutionSample;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;

// Dashboard aggregates (spec 4.4). All windows are a rolling trailing 7 days ending "now", not a
// calendar week -- no Monday-start/timezone convention exists elsewhere in this codebase to anchor one.
// These are synthesized aggregates with no single source entity, so response DTOs are built
// directly here instead of going through a service/mapper class.
@org.springframework.stereotype.Service
public class DashboardService {

    private static final Duration WINDOW = Duration.ofDays(7);

    private final IncidentRepository incidentRepository;
    private final IncidentStateMachine stateMachine;
    private final SlaCalculator slaCalculator;
    private final Clock clock;

    public DashboardService(
            IncidentRepository incidentRepository,
            IncidentStateMachine stateMachine,
            SlaCalculator slaCalculator,
            Clock clock) {
        this.incidentRepository = incidentRepository;
        this.stateMachine = stateMachine;
        this.slaCalculator = slaCalculator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse summary() {
        Instant now = clock.instant();
        Instant windowStart = now.minus(WINDOW);
        Set<IncidentStatus> terminalStatuses = terminalStatuses();

        long openIncidents = incidentRepository.countByStatusNotIn(terminalStatuses);
        long unassignedIncidents = incidentRepository.countByAssigneeIsNullAndStatusNotIn(terminalStatuses);
        long slaBreachesThisWeek = countLiveBreaches(windowStart, now, terminalStatuses);
        List<ResolutionSample> samples = incidentRepository.findResolutionSamplesSince(windowStart);

        DashboardKpiResponse kpis = new DashboardKpiResponse(
                openIncidents, unassignedIncidents, slaBreachesThisWeek, averageMinutes(samples));
        List<PriorityCountResponse> priorityDistribution = priorityDistribution(terminalStatuses);
        List<MttrTrendPointResponse> mttrTrend = mttrTrend(samples, now);

        return new DashboardSummaryResponse(kpis, priorityDistribution, mttrTrend);
    }

    // Re-evaluates each candidate via SlaCalculator instead of trusting persisted sla_breached_at --
    // an open incident's SLA state can go stale between writes (terminal ones are trusted as-is).
    private long countLiveBreaches(Instant windowStart, Instant now, Set<IncidentStatus> terminalStatuses) {
        List<Incident> candidates = incidentRepository.findSlaBreachEvaluationCandidates(windowStart, terminalStatuses);
        return candidates.stream()
                .map(incident -> slaCalculator.evaluate(incident, now).breachedAt())
                .filter(breachedAt -> breachedAt != null && !breachedAt.isBefore(windowStart))
                .count();
    }

    private List<PriorityCountResponse> priorityDistribution(Set<IncidentStatus> terminalStatuses) {
        Map<Priority, Long> counts = new EnumMap<>(Priority.class);
        for (Priority priority : Priority.values()) {
            counts.put(priority, 0L);
        }
        for (PriorityCount row : incidentRepository.countOpenIncidentsByPriority(terminalStatuses)) {
            counts.put(row.getPriority(), row.getCount());
        }
        return counts.entrySet().stream()
                .map(entry -> new PriorityCountResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    private Double averageMinutes(List<ResolutionSample> samples) {
        List<Double> minutes = toMinutes(samples);
        if (minutes.isEmpty()) {
            return null;
        }
        return minutes.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    // Oldest to newest, 7 days ending today (UTC); no-resolution days are null, not 0.
    private List<MttrTrendPointResponse> mttrTrend(List<ResolutionSample> samples, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        Map<LocalDate, List<Double>> byDay = samples.stream()
                .filter(sample -> sample.getSlaStartedAt() != null && sample.getSlaResolvedAt() != null)
                .collect(Collectors.groupingBy(
                        sample -> sample.getSlaResolvedAt().atZone(ZoneOffset.UTC).toLocalDate(),
                        Collectors.mapping(this::minutesBetween, Collectors.toList())));

        List<MttrTrendPointResponse> trend = new ArrayList<>();
        for (int daysAgo = 6; daysAgo >= 0; daysAgo--) {
            LocalDate date = today.minusDays(daysAgo);
            List<Double> dayMinutes = byDay.get(date);
            Double average = dayMinutes == null || dayMinutes.isEmpty()
                    ? null
                    : dayMinutes.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            trend.add(new MttrTrendPointResponse(date, average));
        }
        return trend;
    }

    private List<Double> toMinutes(List<ResolutionSample> samples) {
        return samples.stream()
                .filter(sample -> sample.getSlaStartedAt() != null && sample.getSlaResolvedAt() != null)
                .map(this::minutesBetween)
                .toList();
    }

    private double minutesBetween(ResolutionSample sample) {
        return Duration.between(sample.getSlaStartedAt(), sample.getSlaResolvedAt()).toSeconds() / 60.0;
    }

    private Set<IncidentStatus> terminalStatuses() {
        return Arrays.stream(IncidentStatus.values())
                .filter(stateMachine::isTerminal)
                .collect(Collectors.toUnmodifiableSet());
    }
}
