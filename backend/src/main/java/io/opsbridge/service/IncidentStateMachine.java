package io.opsbridge.service;

import static io.opsbridge.domain.enums.IncidentStatus.CANCELLED;
import static io.opsbridge.domain.enums.IncidentStatus.INVESTIGATING;
import static io.opsbridge.domain.enums.IncidentStatus.MITIGATED;
import static io.opsbridge.domain.enums.IncidentStatus.OPEN;
import static io.opsbridge.domain.enums.IncidentStatus.RESOLVED;
import static io.opsbridge.domain.enums.IncidentStatus.TRIAGED;

import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.support.error.IllegalStatusTransitionException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

// Incident workflow per spec 4.1: self-transitions illegal, RESOLVED/CANCELLED terminal.
// Known gap left as-is: spec only allows CANCELLED from OPEN/TRIAGED, so an incident stuck in
// INVESTIGATING or MITIGATED can never be cancelled or walked back to a cancellable state --
// that's a product call, not ours to invent.
@Component
public class IncidentStateMachine {

    private static final Map<IncidentStatus, Set<IncidentStatus>> LEGAL_TRANSITIONS =
            new EnumMap<>(IncidentStatus.class);

    static {
        LEGAL_TRANSITIONS.put(OPEN, Set.of(TRIAGED, CANCELLED));
        LEGAL_TRANSITIONS.put(TRIAGED, Set.of(INVESTIGATING, CANCELLED));
        LEGAL_TRANSITIONS.put(INVESTIGATING, Set.of(MITIGATED, OPEN));
        LEGAL_TRANSITIONS.put(MITIGATED, Set.of(RESOLVED, INVESTIGATING));
        LEGAL_TRANSITIONS.put(RESOLVED, Set.of());
        LEGAL_TRANSITIONS.put(CANCELLED, Set.of());
    }

    public boolean isLegal(IncidentStatus from, IncidentStatus to) {
        return LEGAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public void assertCanTransition(IncidentStatus from, IncidentStatus to) {
        if (!isLegal(from, to)) {
            throw new IllegalStatusTransitionException(from, to);
        }
    }

    public Set<IncidentStatus> legalTargetsFrom(IncidentStatus from) {
        return LEGAL_TRANSITIONS.getOrDefault(from, Set.of());
    }

    public boolean isTerminal(IncidentStatus status) {
        return LEGAL_TRANSITIONS.getOrDefault(status, Set.of()).isEmpty();
    }
}
