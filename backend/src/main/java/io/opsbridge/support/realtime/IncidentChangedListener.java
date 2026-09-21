package io.opsbridge.support.realtime;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.repository.IncidentRepository;
import io.opsbridge.service.mapper.IncidentMapper;
import java.time.Clock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

// AFTER_COMMIT matters -- broadcasting inside the mutating transaction could announce a change
// that then rolls back, or let a refetching client read pre-commit state. By the time this runs
// the write is durable.
// Re-reads the incident in its own transaction since the original has closed and open-in-view is
// disabled; lazy associations would otherwise fail to load while mapping.
@Component
public class IncidentChangedListener {

    private final IncidentRepository incidentRepository;
    private final IncidentMapper incidentMapper;
    private final LiveEventBroadcaster broadcaster;
    private final Clock clock;

    public IncidentChangedListener(
            IncidentRepository incidentRepository,
            IncidentMapper incidentMapper,
            LiveEventBroadcaster broadcaster,
            Clock clock) {
        this.incidentRepository = incidentRepository;
        this.incidentMapper = incidentMapper;
        this.broadcaster = broadcaster;
        this.clock = clock;
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onIncidentChanged(IncidentChangedEvent event) {
        incidentRepository
                .findById(event.incidentId())
                .map(this::toEvent)
                .ifPresent(broadcaster::broadcast);
    }

    private LiveEvent toEvent(Incident incident) {
        return LiveEvent.incidentUpdated(incidentMapper.toSummary(incident), clock.instant());
    }
}
