package io.opsbridge.service;

import static io.opsbridge.domain.enums.IncidentStatus.CANCELLED;
import static io.opsbridge.domain.enums.IncidentStatus.INVESTIGATING;
import static io.opsbridge.domain.enums.IncidentStatus.MITIGATED;
import static io.opsbridge.domain.enums.IncidentStatus.OPEN;
import static io.opsbridge.domain.enums.IncidentStatus.RESOLVED;
import static io.opsbridge.domain.enums.IncidentStatus.TRIAGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.support.error.IllegalStatusTransitionException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

// Covers every ordered (from, to) pair (36 cases) against a transition table transcribed
// independently from the spec, so a change to the production table can't silently widen the workflow.
class IncidentStateMachineTest {

    // Transcribed from the spec diagram, not the production class.
    private static final Map<IncidentStatus, Set<IncidentStatus>> EXPECTED_LEGAL = Map.of(
            OPEN, Set.of(TRIAGED, CANCELLED),
            TRIAGED, Set.of(INVESTIGATING, CANCELLED),
            INVESTIGATING, Set.of(MITIGATED, OPEN),
            MITIGATED, Set.of(RESOLVED, INVESTIGATING),
            RESOLVED, Set.of(),
            CANCELLED, Set.of());

    private final IncidentStateMachine stateMachine = new IncidentStateMachine();

    static Stream<Arguments> allTransitionPairs() {
        return Arrays.stream(IncidentStatus.values())
                .flatMap(from -> Arrays.stream(IncidentStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allTransitionPairs")
    void isLegal_matchesTheSpecifiedWorkflowForEveryStatusPair(IncidentStatus from, IncidentStatus to) {
        boolean expectedLegal = EXPECTED_LEGAL.get(from).contains(to);

        assertThat(stateMachine.isLegal(from, to)).isEqualTo(expectedLegal);

        if (expectedLegal) {
            assertThatCode(() -> stateMachine.assertCanTransition(from, to)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> stateMachine.assertCanTransition(from, to))
                    .isInstanceOf(IllegalStatusTransitionException.class)
                    .hasMessageContaining(from.name())
                    .hasMessageContaining(to.name());
        }
    }

    @ParameterizedTest
    @EnumSource(IncidentStatus.class)
    void isLegal_rejectsEverySelfTransition(IncidentStatus status) {
        assertThat(stateMachine.isLegal(status, status)).isFalse();
    }

    @Test
    void countsOfLegalAndIllegalTransitionsMatchTheSpecification() {
        long legal = allTransitionPairs()
                .filter(args -> stateMachine.isLegal(
                        (IncidentStatus) args.get()[0], (IncidentStatus) args.get()[1]))
                .count();

        assertThat(legal).isEqualTo(8);
        assertThat(allTransitionPairs().count() - legal).isEqualTo(28);
    }

    @Test
    void resolvedAndCancelledAreTerminal() {
        assertThat(stateMachine.isTerminal(RESOLVED)).isTrue();
        assertThat(stateMachine.isTerminal(CANCELLED)).isTrue();
        assertThat(stateMachine.legalTargetsFrom(RESOLVED)).isEmpty();
        assertThat(stateMachine.legalTargetsFrom(CANCELLED)).isEmpty();
    }

    @Test
    void nonTerminalStatusesAlwaysHaveSomewhereToGo() {
        Stream.of(OPEN, TRIAGED, INVESTIGATING, MITIGATED)
                .forEach(status -> {
                    assertThat(stateMachine.isTerminal(status)).isFalse();
                    assertThat(stateMachine.legalTargetsFrom(status)).isNotEmpty();
                });
    }

    @Test
    void emergencyResolutionShortcutsAreRejected() {
        // Spec 4.1: triage and investigation cannot be skipped.
        assertThat(stateMachine.isLegal(OPEN, RESOLVED)).isFalse();
        assertThat(stateMachine.isLegal(TRIAGED, RESOLVED)).isFalse();
        assertThat(stateMachine.isLegal(INVESTIGATING, RESOLVED)).isFalse();
    }

    @Test
    void cancellationIsUnreachableOnceInvestigationStarts() {
        // Known product gap: past TRIAGED there's no path to CANCELLED or back to a cancellable status.
        assertThat(stateMachine.isLegal(INVESTIGATING, CANCELLED)).isFalse();
        assertThat(stateMachine.isLegal(MITIGATED, CANCELLED)).isFalse();
        assertThat(stateMachine.isLegal(INVESTIGATING, TRIAGED)).isFalse();
    }
}
