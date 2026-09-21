package io.opsbridge.repository;

import io.opsbridge.domain.entity.IncidentActivity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentActivityRepository extends JpaRepository<IncidentActivity, UUID> {

    List<IncidentActivity> findByIncidentIdOrderByCreatedAtAsc(UUID incidentId);

    List<IncidentActivity> findByServiceIdOrderByCreatedAtAsc(UUID serviceId);

    // Backs the incident timeline (GET /incidents/{id}/activity); actor eager-fetched since open-in-view is off.
    @EntityGraph(attributePaths = "actor")
    Page<IncidentActivity> findByIncidentId(UUID incidentId, Pageable pageable);
}
