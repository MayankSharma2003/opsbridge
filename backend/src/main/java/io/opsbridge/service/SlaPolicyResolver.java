package io.opsbridge.service;

import io.opsbridge.domain.entity.SlaPolicy;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.repository.SlaPolicyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Exact severity match wins, else the priority's catch-all row (severity IS NULL); only active
// policies count.
@Component
public class SlaPolicyResolver {

    private static final Logger log = LoggerFactory.getLogger(SlaPolicyResolver.class);

    private final SlaPolicyRepository slaPolicyRepository;

    public SlaPolicyResolver(SlaPolicyRepository slaPolicyRepository) {
        this.slaPolicyRepository = slaPolicyRepository;
    }

    // Null means no policy configured -- intake must not fail on an SLA gap, so callers just leave
    // SLA fields empty.
    public SlaPolicy resolve(Priority priority, Severity severity) {
        return slaPolicyRepository
                .findByPriorityAndSeverityAndActiveTrue(priority, severity)
                .or(() -> slaPolicyRepository.findByPriorityAndSeverityIsNullAndActiveTrue(priority))
                .orElseGet(() -> {
                    log.warn(
                            "No active SLA policy configured for priority={} severity={}; "
                                    + "incident will have no SLA targets.",
                            priority,
                            severity);
                    return null;
                });
    }
}
