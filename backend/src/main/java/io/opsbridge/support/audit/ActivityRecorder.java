package io.opsbridge.support.audit;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.entity.IncidentActivity;
import io.opsbridge.domain.entity.Service;
import io.opsbridge.domain.entity.User;
import io.opsbridge.domain.enums.ActivityEventType;
import io.opsbridge.repository.IncidentActivityRepository;
import java.time.Clock;
import org.springframework.stereotype.Component;

// Single place workflow mutations append to the activity log; called inside the caller's existing
// transaction so the audit row and its mutation commit together or not at all.
// Timestamps come from the injected Clock, not Instant.now(), so the activity row agrees with SLA
// math computed at the same "now".
@Component
public class ActivityRecorder {

    private final IncidentActivityRepository incidentActivityRepository;
    private final Clock clock;

    public ActivityRecorder(IncidentActivityRepository incidentActivityRepository, Clock clock) {
        this.incidentActivityRepository = incidentActivityRepository;
        this.clock = clock;
    }

    public IncidentActivity record(
            Incident incident,
            User actor,
            ActivityEventType eventType,
            String fromValue,
            String toValue,
            String comment) {
        return recordFieldChange(incident, actor, eventType, null, fromValue, toValue, comment);
    }

    public IncidentActivity record(Incident incident, User actor, ActivityEventType eventType, String comment) {
        return recordFieldChange(incident, actor, eventType, null, null, null, comment);
    }

    // fieldName distinguishes which attribute changed when an event can touch several.
    public IncidentActivity recordFieldChange(
            Incident incident,
            User actor,
            ActivityEventType eventType,
            String fieldName,
            String fromValue,
            String toValue,
            String comment) {
        return incidentActivityRepository.save(IncidentActivity.forIncident(
                incident, actor, eventType, fieldName, fromValue, toValue, comment, clock.instant()));
    }

    public IncidentActivity recordServiceEvent(
            Service service,
            User actor,
            ActivityEventType eventType,
            String fromValue,
            String toValue,
            String comment) {
        return recordServiceFieldChange(service, actor, eventType, null, fromValue, toValue, comment);
    }

    // One row per changed service attribute, so from/to stay meaningful scalars.
    public IncidentActivity recordServiceFieldChange(
            Service service,
            User actor,
            ActivityEventType eventType,
            String fieldName,
            String fromValue,
            String toValue,
            String comment) {
        return incidentActivityRepository.save(IncidentActivity.forService(
                service, actor, eventType, fieldName, fromValue, toValue, comment, clock.instant()));
    }
}
