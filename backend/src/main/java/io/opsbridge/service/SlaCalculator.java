package io.opsbridge.service;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.entity.SlaPolicy;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.SlaState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

// Pure math, no I/O -- "now" is always passed in so tests are deterministic.
// P3/P4 "business days" are seeded as flat 8h/day minutes for now; real business-calendar support
// (timezone, holidays) needs spec input we don't have yet.
@Component
public class SlaCalculator {

    // % of the target window elapsed before an unmet stage flips to AT_RISK.
    private static final int AT_RISK_THRESHOLD_PERCENT = 80;

    public SlaTargets computeTargets(SlaPolicy policy, Instant startedAt) {
        if (policy == null || startedAt == null) {
            return SlaTargets.none();
        }
        return new SlaTargets(
                startedAt.plus(Duration.ofMinutes(policy.getResponseTargetMinutes())),
                startedAt.plus(Duration.ofMinutes(policy.getMitigationTargetMinutes())),
                startedAt.plus(Duration.ofMinutes(policy.getResolutionTargetMinutes())));
    }

    // Cancelled incidents freeze their SLA clock -- no accruing breaches after the fact.
    public SlaEvaluation evaluate(Incident incident, Instant now) {
        if (incident.getStatus() == IncidentStatus.CANCELLED) {
            return new SlaEvaluation(incident.getSlaState(), incident.getSlaBreachedAt());
        }

        List<Instant> breaches = new ArrayList<>();
        addBreachIfAny(breaches, incident.getSlaRespondedAt(), incident.getSlaResponseTargetAt(), now);
        addBreachIfAny(breaches, incident.getSlaMitigatedAt(), incident.getSlaMitigationTargetAt(), now);
        addBreachIfAny(breaches, incident.getSlaResolvedAt(), incident.getSlaResolutionTargetAt(), now);

        if (!breaches.isEmpty()) {
            return new SlaEvaluation(SlaState.BREACHED, breaches.stream().min(Instant::compareTo).orElseThrow());
        }
        if (incident.getSlaResolvedAt() != null) {
            return new SlaEvaluation(SlaState.MET, null);
        }
        if (isAtRisk(incident, now)) {
            return new SlaEvaluation(SlaState.AT_RISK, null);
        }
        return new SlaEvaluation(SlaState.ON_TRACK, null);
    }

    // Breach instant is the target itself (the moment it was missed), not achievedAt/now -- exact
    // on-time is not a breach.
    private void addBreachIfAny(List<Instant> breaches, Instant achievedAt, Instant targetAt, Instant now) {
        if (targetAt == null) {
            return;
        }
        Instant comparedAt = achievedAt != null ? achievedAt : now;
        if (comparedAt.isAfter(targetAt)) {
            breaches.add(targetAt);
        }
    }

    private boolean isAtRisk(Incident incident, Instant now) {
        Instant startedAt = incident.getSlaStartedAt();
        if (startedAt == null) {
            return false;
        }
        Instant nextTarget = nextUnmetTarget(incident);
        if (nextTarget == null) {
            return false;
        }
        Duration window = Duration.between(startedAt, nextTarget);
        if (window.isZero() || window.isNegative()) {
            return false;
        }
        Instant atRiskFrom = startedAt.plus(window.multipliedBy(AT_RISK_THRESHOLD_PERCENT).dividedBy(100));
        return !now.isBefore(atRiskFrom);
    }

    // null once every stage is achieved.
    private Instant nextUnmetTarget(Incident incident) {
        List<Instant> outstanding = new ArrayList<>();
        addIfOutstanding(outstanding, incident.getSlaRespondedAt(), incident.getSlaResponseTargetAt());
        addIfOutstanding(outstanding, incident.getSlaMitigatedAt(), incident.getSlaMitigationTargetAt());
        addIfOutstanding(outstanding, incident.getSlaResolvedAt(), incident.getSlaResolutionTargetAt());
        return outstanding.stream().min(Instant::compareTo).orElse(null);
    }

    private void addIfOutstanding(List<Instant> outstanding, Instant achievedAt, Instant targetAt) {
        if (achievedAt == null && targetAt != null) {
            outstanding.add(targetAt);
        }
    }

    public record SlaTargets(Instant responseTargetAt, Instant mitigationTargetAt, Instant resolutionTargetAt) {

        static SlaTargets none() {
            return new SlaTargets(null, null, null);
        }
    }

    public record SlaEvaluation(SlaState state, Instant breachedAt) {
    }
}
