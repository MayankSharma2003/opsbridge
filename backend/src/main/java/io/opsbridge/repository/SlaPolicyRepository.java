package io.opsbridge.repository;

import io.opsbridge.domain.entity.SlaPolicy;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Severity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlaPolicyRepository extends JpaRepository<SlaPolicy, UUID> {

    Optional<SlaPolicy> findByPriorityAndSeverityAndActiveTrue(Priority priority, Severity severity);

    Optional<SlaPolicy> findByPriorityAndSeverityIsNullAndActiveTrue(Priority priority);
}
