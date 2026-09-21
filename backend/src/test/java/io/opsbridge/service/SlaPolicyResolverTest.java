package io.opsbridge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opsbridge.domain.entity.SlaPolicy;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.repository.SlaPolicyRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SlaPolicyResolverTest {

    private SlaPolicyRepository slaPolicyRepository;
    private SlaPolicyResolver resolver;

    @BeforeEach
    void setUp() {
        slaPolicyRepository = mock(SlaPolicyRepository.class);
        resolver = new SlaPolicyResolver(slaPolicyRepository);
    }

    @Test
    void resolve_prefersTheExactSeverityMatchOverTheCatchAll() {
        SlaPolicy exact = new SlaPolicy(Priority.P1, Severity.SEV1, 5, 30, 240);
        SlaPolicy catchAll = new SlaPolicy(Priority.P1, null, 15, 60, 480);
        when(slaPolicyRepository.findByPriorityAndSeverityAndActiveTrue(Priority.P1, Severity.SEV1))
                .thenReturn(Optional.of(exact));
        when(slaPolicyRepository.findByPriorityAndSeverityIsNullAndActiveTrue(Priority.P1))
                .thenReturn(Optional.of(catchAll));

        SlaPolicy result = resolver.resolve(Priority.P1, Severity.SEV1);

        assertThat(result).isEqualTo(exact);
        // The exact match already answers it, so the catch-all shouldn't even be consulted.
        verify(slaPolicyRepository, never()).findByPriorityAndSeverityIsNullAndActiveTrue(Priority.P1);
    }

    @Test
    void resolve_fallsBackToThePriorityCatchAllWhenNoExactMatchExists() {
        SlaPolicy catchAll = new SlaPolicy(Priority.P2, null, 15, 60, 480);
        when(slaPolicyRepository.findByPriorityAndSeverityAndActiveTrue(Priority.P2, Severity.SEV3))
                .thenReturn(Optional.empty());
        when(slaPolicyRepository.findByPriorityAndSeverityIsNullAndActiveTrue(Priority.P2))
                .thenReturn(Optional.of(catchAll));

        SlaPolicy result = resolver.resolve(Priority.P2, Severity.SEV3);

        assertThat(result).isEqualTo(catchAll);
    }

    @Test
    void resolve_returnsNullWithoutThrowingWhenNoPolicyIsConfigured() {
        when(slaPolicyRepository.findByPriorityAndSeverityAndActiveTrue(Priority.P4, Severity.SEV4))
                .thenReturn(Optional.empty());
        when(slaPolicyRepository.findByPriorityAndSeverityIsNullAndActiveTrue(Priority.P4))
                .thenReturn(Optional.empty());

        // A missing policy is a recoverable null, not an exception -- intake must not fail on it.
        assertThat(resolver.resolve(Priority.P4, Severity.SEV4)).isNull();
    }
}
