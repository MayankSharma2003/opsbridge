package io.opsbridge.service;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.SlaState;
import io.opsbridge.repository.IncidentRepository;
import io.opsbridge.service.mapper.IncidentMapper;
import io.opsbridge.support.realtime.LiveEvent;
import io.opsbridge.support.realtime.LiveEventBroadcaster;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Breach is the one state change with no writer -- everything else happens via an endpoint call, a
// breach is just a deadline passing unseen. IncidentService recomputes SLA state on read so the API
// stays correct, but something still has to poll and push; this is that.
// Deliberately doesn't write to the DB: persisting would bump @Version and hand out spurious 409s to
// anyone with the incident open (same hazard IncidentService.assign avoids for no-op reassignment),
// and an SLA_BREACHED activity row isn't possible yet since incident_activity.actor_id is NOT NULL
// and a breach has no human actor.
// Consequence: a restart forgets what it already announced, so a still-breached incident may get
// re-announced once -- harmless, clients just treat these as invalidation hints and refetch.
@Component
public class SlaBreachMonitor {

    private static final Set<IncidentStatus> TERMINAL_STATUSES =
            Set.of(IncidentStatus.RESOLVED, IncidentStatus.CANCELLED);

    /** Bounds the candidate query; matches the dashboard's "breaches this week" window. */
    private static final Duration LOOKBACK = Duration.ofDays(7);

    private final IncidentRepository incidentRepository;
    private final SlaCalculator slaCalculator;
    private final IncidentMapper incidentMapper;
    private final LiveEventBroadcaster broadcaster;
    private final Clock clock;

    // Already-announced set -- push only on the edge into BREACHED, not every sweep.
    private final Set<UUID> announced = ConcurrentHashMap.newKeySet();

    public SlaBreachMonitor(
            IncidentRepository incidentRepository,
            SlaCalculator slaCalculator,
            IncidentMapper incidentMapper,
            LiveEventBroadcaster broadcaster,
            Clock clock) {
        this.incidentRepository = incidentRepository;
        this.slaCalculator = slaCalculator;
        this.incidentMapper = incidentMapper;
        this.broadcaster = broadcaster;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${opsbridge.realtime.sla-sweep-ms:30000}")
    @Transactional(readOnly = true)
    public void sweep() {
        Instant now = clock.instant();
        for (Incident incident : incidentRepository.findSlaBreachEvaluationCandidates(
                now.minus(LOOKBACK), TERMINAL_STATUSES)) {
            evaluate(incident, now);
        }
    }

    private void evaluate(Incident incident, Instant now) {
        boolean breached = slaCalculator.evaluate(incident, now).state() == SlaState.BREACHED;

        if (!breached) {
            // Lets it re-announce if it breaches again later (e.g. after escalation tightens
            // targets), and keeps the set from growing forever.
            announced.remove(incident.getId());
            return;
        }
        if (!announced.add(incident.getId())) {
            return;
        }

        // In-memory only -- set state so the pushed payload matches a live GET, without marking the
        // entity dirty for a write.
        incident.setSlaState(SlaState.BREACHED);
        broadcaster.broadcast(LiveEvent.slaBreached(incidentMapper.toSummary(incident), now));
    }
}
