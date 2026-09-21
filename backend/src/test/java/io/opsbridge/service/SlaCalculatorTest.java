package io.opsbridge.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.entity.SlaPolicy;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.domain.enums.SlaState;
import io.opsbridge.service.SlaCalculator.SlaEvaluation;
import io.opsbridge.service.SlaCalculator.SlaTargets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SlaCalculatorTest {

    private static final Instant START = Instant.parse("2026-03-01T09:00:00Z");

    private final SlaCalculator calculator = new SlaCalculator();

    @ParameterizedTest(name = "{0}: {1}/{2}/{3} minutes")
    @CsvSource({
        // Matches the V3 seed data / spec 4.2 policy table.
        "P1, 5, 30, 240",
        "P2, 15, 60, 480",
        "P3, 60, 240, 960",
        "P4, 240, 480, 2400"
    })
    void computeTargets_addsPolicyMinutesToTheSlaStart(
            Priority priority, int response, int mitigation, int resolution) {
        SlaPolicy policy = new SlaPolicy(priority, null, response, mitigation, resolution);

        SlaTargets targets = calculator.computeTargets(policy, START);

        assertThat(targets.responseTargetAt()).isEqualTo(START.plus(Duration.ofMinutes(response)));
        assertThat(targets.mitigationTargetAt()).isEqualTo(START.plus(Duration.ofMinutes(mitigation)));
        assertThat(targets.resolutionTargetAt()).isEqualTo(START.plus(Duration.ofMinutes(resolution)));
    }

    @Test
    void computeTargets_yieldsNoTargetsWhenNoPolicyApplies() {
        SlaTargets targets = calculator.computeTargets(null, START);

        assertThat(targets.responseTargetAt()).isNull();
        assertThat(targets.mitigationTargetAt()).isNull();
        assertThat(targets.resolutionTargetAt()).isNull();
    }

    @Test
    void computeTargets_yieldsNoTargetsWithoutAStart() {
        SlaTargets targets = calculator.computeTargets(p1Policy(), null);

        assertThat(targets.responseTargetAt()).isNull();
    }

    @Test
    void evaluate_isOnTrackImmediatelyAfterCreation() {
        Incident incident = p1Incident();

        SlaEvaluation evaluation = calculator.evaluate(incident, START);

        assertThat(evaluation.state()).isEqualTo(SlaState.ON_TRACK);
        assertThat(evaluation.breachedAt()).isNull();
    }

    @Test
    void evaluate_isOnTrackJustBeforeTheAtRiskThreshold() {
        // P1's 5-minute target puts the 80% at-risk threshold at 4 minutes.
        Incident incident = p1Incident();

        SlaEvaluation evaluation = calculator.evaluate(incident, START.plus(Duration.ofSeconds(239)));

        assertThat(evaluation.state()).isEqualTo(SlaState.ON_TRACK);
    }

    @Test
    void evaluate_isAtRiskOnceEightyPercentOfTheWindowHasElapsed() {
        Incident incident = p1Incident();

        SlaEvaluation evaluation = calculator.evaluate(incident, START.plus(Duration.ofMinutes(4)));

        assertThat(evaluation.state()).isEqualTo(SlaState.AT_RISK);
        assertThat(evaluation.breachedAt()).isNull();
    }

    @Test
    void evaluate_isAtRiskButNotBreachedExactlyAtTheTarget() {
        Incident incident = p1Incident();

        SlaEvaluation evaluation = calculator.evaluate(incident, START.plus(Duration.ofMinutes(5)));

        assertThat(evaluation.state()).isEqualTo(SlaState.AT_RISK);
        assertThat(evaluation.breachedAt()).isNull();
    }

    @Test
    void evaluate_measuresAtRiskAgainstTheNextOutstandingTarget() {
        // Responded on time, so the 30-min mitigation target is now the one at risk.
        Incident incident = p1Incident();
        incident.setSlaRespondedAt(START.plus(Duration.ofMinutes(2)));

        assertThat(calculator.evaluate(incident, START.plus(Duration.ofMinutes(23))).state())
                .isEqualTo(SlaState.ON_TRACK);
        assertThat(calculator.evaluate(incident, START.plus(Duration.ofMinutes(24))).state())
                .isEqualTo(SlaState.AT_RISK);
    }

    @Test
    void evaluate_breachesWhenAnOutstandingTargetPasses() {
        Incident incident = p1Incident();

        SlaEvaluation evaluation = calculator.evaluate(incident, START.plus(Duration.ofMinutes(6)));

        assertThat(evaluation.state()).isEqualTo(SlaState.BREACHED);
        assertThat(evaluation.breachedAt()).isEqualTo(START.plus(Duration.ofMinutes(5)));
    }

    @Test
    void evaluate_doesNotBreachWhenAStageIsAchievedExactlyOnTheTarget() {
        Incident incident = p1Incident();
        incident.setSlaRespondedAt(START.plus(Duration.ofMinutes(5)));

        SlaEvaluation evaluation = calculator.evaluate(incident, START.plus(Duration.ofMinutes(5)));

        assertThat(evaluation.state()).isNotEqualTo(SlaState.BREACHED);
    }

    @Test
    void evaluate_breachesWhenAStageWasAchievedLate() {
        Incident incident = p1Incident();
        incident.setSlaRespondedAt(START.plus(Duration.ofMinutes(7)));

        SlaEvaluation evaluation = calculator.evaluate(incident, START.plus(Duration.ofMinutes(8)));

        assertThat(evaluation.state()).isEqualTo(SlaState.BREACHED);
        assertThat(evaluation.breachedAt()).isEqualTo(START.plus(Duration.ofMinutes(5)));
    }

    @Test
    void evaluate_reportsTheEarliestBreachWhenSeveralTargetsAreMissed() {
        Incident incident = p1Incident();
        incident.setSlaRespondedAt(START.plus(Duration.ofMinutes(10)));
        incident.setSlaMitigatedAt(START.plus(Duration.ofMinutes(45)));

        SlaEvaluation evaluation = calculator.evaluate(incident, START.plus(Duration.ofMinutes(50)));

        assertThat(evaluation.state()).isEqualTo(SlaState.BREACHED);
        assertThat(evaluation.breachedAt()).isEqualTo(START.plus(Duration.ofMinutes(5)));
    }

    @Test
    void evaluate_breachesOnALateResolutionEvenWhenEarlierStagesWereOnTime() {
        Incident incident = p1Incident();
        incident.setSlaRespondedAt(START.plus(Duration.ofMinutes(1)));
        incident.setSlaMitigatedAt(START.plus(Duration.ofMinutes(10)));
        incident.setSlaResolvedAt(START.plus(Duration.ofMinutes(300)));
        incident.setStatus(IncidentStatus.RESOLVED);

        SlaEvaluation evaluation = calculator.evaluate(incident, START.plus(Duration.ofMinutes(300)));

        assertThat(evaluation.state()).isEqualTo(SlaState.BREACHED);
        assertThat(evaluation.breachedAt()).isEqualTo(START.plus(Duration.ofMinutes(240)));
    }

    @Test
    void evaluate_isMetWhenEveryStageLandedWithinTarget() {
        Incident incident = p1Incident();
        incident.setSlaRespondedAt(START.plus(Duration.ofMinutes(3)));
        incident.setSlaMitigatedAt(START.plus(Duration.ofMinutes(20)));
        incident.setSlaResolvedAt(START.plus(Duration.ofMinutes(100)));
        incident.setStatus(IncidentStatus.RESOLVED);

        SlaEvaluation evaluation = calculator.evaluate(incident, START.plus(Duration.ofDays(30)));

        assertThat(evaluation.state()).isEqualTo(SlaState.MET);
        assertThat(evaluation.breachedAt()).isNull();
    }

    @Test
    void evaluate_staysMetLongAfterResolutionRatherThanDriftingBackToBreached() {
        Incident incident = p1Incident();
        incident.setSlaRespondedAt(START.plus(Duration.ofMinutes(1)));
        incident.setSlaMitigatedAt(START.plus(Duration.ofMinutes(2)));
        incident.setSlaResolvedAt(START.plus(Duration.ofMinutes(3)));
        incident.setStatus(IncidentStatus.RESOLVED);

        assertThat(calculator.evaluate(incident, START.plus(Duration.ofDays(365))).state())
                .isEqualTo(SlaState.MET);
    }

    @Test
    void evaluate_reopensTheMitigationTargetWhenMitigationIsUndone() {
        // MITIGATED -> INVESTIGATING clears slaMitigatedAt, so that target is outstanding again.
        Incident incident = p1Incident();
        incident.setSlaRespondedAt(START.plus(Duration.ofMinutes(1)));
        incident.setSlaMitigatedAt(null);
        incident.setStatus(IncidentStatus.INVESTIGATING);

        SlaEvaluation evaluation = calculator.evaluate(incident, START.plus(Duration.ofMinutes(31)));

        assertThat(evaluation.state()).isEqualTo(SlaState.BREACHED);
        assertThat(evaluation.breachedAt()).isEqualTo(START.plus(Duration.ofMinutes(30)));
    }

    @Test
    void evaluate_freezesTheClockOnceCancelled() {
        Incident incident = p1Incident();
        incident.setStatus(IncidentStatus.CANCELLED);
        incident.setSlaState(SlaState.ON_TRACK);

        SlaEvaluation evaluation = calculator.evaluate(incident, START.plus(Duration.ofDays(30)));

        assertThat(evaluation.state()).isEqualTo(SlaState.ON_TRACK);
        assertThat(evaluation.breachedAt()).isNull();
    }

    @Test
    void evaluate_preservesABreachThatHappenedBeforeCancellation() {
        Incident incident = p1Incident();
        incident.setStatus(IncidentStatus.CANCELLED);
        incident.setSlaState(SlaState.BREACHED);
        incident.setSlaBreachedAt(START.plus(Duration.ofMinutes(5)));

        SlaEvaluation evaluation = calculator.evaluate(incident, START.plus(Duration.ofDays(30)));

        assertThat(evaluation.state()).isEqualTo(SlaState.BREACHED);
        assertThat(evaluation.breachedAt()).isEqualTo(START.plus(Duration.ofMinutes(5)));
    }

    @Test
    void evaluate_staysOnTrackWhenNoTargetsAreConfigured() {
        Incident incident = incidentWithoutTargets();

        SlaEvaluation evaluation = calculator.evaluate(incident, START.plus(Duration.ofDays(30)));

        assertThat(evaluation.state()).isEqualTo(SlaState.ON_TRACK);
        assertThat(evaluation.breachedAt()).isNull();
    }

    @Test
    void evaluate_isMetWhenResolvedWithNoTargetsConfigured() {
        Incident incident = incidentWithoutTargets();
        incident.setSlaResolvedAt(START.plus(Duration.ofDays(1)));
        incident.setStatus(IncidentStatus.RESOLVED);

        assertThat(calculator.evaluate(incident, START.plus(Duration.ofDays(30))).state())
                .isEqualTo(SlaState.MET);
    }

    private static SlaPolicy p1Policy() {
        return new SlaPolicy(Priority.P1, null, 5, 30, 240);
    }

    private Incident p1Incident() {
        Incident incident = incidentWithoutTargets();
        SlaTargets targets = calculator.computeTargets(p1Policy(), START);
        incident.setSlaResponseTargetAt(targets.responseTargetAt());
        incident.setSlaMitigationTargetAt(targets.mitigationTargetAt());
        incident.setSlaResolutionTargetAt(targets.resolutionTargetAt());
        return incident;
    }

    private static Incident incidentWithoutTargets() {
        Incident incident =
                new Incident("INC-1000", "Checkout latency", null, null, null, Priority.P1, Severity.SEV2);
        incident.setSlaStartedAt(START);
        return incident;
    }
}
