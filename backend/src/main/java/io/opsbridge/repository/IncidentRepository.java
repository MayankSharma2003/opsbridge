package io.opsbridge.repository;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// JpaSpecificationExecutor backs the filtered/search list (see IncidentSpecifications).
// list() always goes through findAll(Specification, Pageable), even unfiltered, since the eager fetch moved there from @EntityGraph.
public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {

    // Eager fetch because open-in-view is off -- DTO mapping happens after the tx closes.
    @Override
    @EntityGraph(attributePaths = {"service", "reporter", "assignee"})
    Optional<Incident> findById(UUID id);

    Optional<Incident> findByPublicKey(String publicKey);

    // Native query: the sequence is schema-owned, not Hibernate's.
    @Query(value = "SELECT nextval('incident_public_key_seq')", nativeQuery = true)
    long nextPublicKeySequenceValue();

    /** Open-incident KPI: the dashboard's definition of "open" is simply "not terminal." */
    long countByStatusNotIn(Collection<IncidentStatus> statuses);

    // Unassigned slice of the open count -- nobody's picked it up yet.
    long countByAssigneeIsNullAndStatusNotIn(Collection<IncidentStatus> statuses);

    @Query("SELECT i.priority AS priority, COUNT(i) AS count FROM Incident i "
            + "WHERE i.status NOT IN :terminalStatuses GROUP BY i.priority")
    List<PriorityCount> countOpenIncidentsByPriority(
            @Param("terminalStatuses") Collection<IncidentStatus> terminalStatuses);

    // Grouped by service+priority so health can tell a P1 apart from a pile of P4s; one query avoids an N+1 across the service list page.
    // Services with nothing open are absent -- caller zero-fills, since GROUP BY can't emit empty groups.
    @Query("SELECT i.service.id AS serviceId, i.priority AS priority, COUNT(i) AS count FROM Incident i "
            + "WHERE i.status NOT IN :terminalStatuses AND i.service.id IN :serviceIds "
            + "GROUP BY i.service.id, i.priority")
    List<ServiceOpenCount> countOpenIncidentsByService(
            @Param("serviceIds") Collection<UUID> serviceIds,
            @Param("terminalStatuses") Collection<IncidentStatus> terminalStatuses);

    // SLA-breach KPI candidates: a terminal incident's slaBreachedAt is fixed once set, so a recent breach is trustworthy as-is.
    // A non-terminal incident's persisted SLA state can go stale, so every open incident is included here for the caller to re-evaluate live via SlaCalculator.
    @Query("SELECT i FROM Incident i WHERE "
            + "(i.slaBreachedAt IS NOT NULL AND i.slaBreachedAt >= :windowStart) "
            + "OR i.status NOT IN :terminalStatuses")
    List<Incident> findSlaBreachEvaluationCandidates(
            @Param("windowStart") Instant windowStart,
            @Param("terminalStatuses") Collection<IncidentStatus> terminalStatuses);

    // Raw (start, resolved) pairs for MTTR; averaging happens in Java, not SQL.
    @Query("SELECT i.slaStartedAt AS slaStartedAt, i.slaResolvedAt AS slaResolvedAt FROM Incident i "
            + "WHERE i.slaResolvedAt >= :windowStart")
    List<ResolutionSample> findResolutionSamplesSince(@Param("windowStart") Instant windowStart);

    interface PriorityCount {
        Priority getPriority();

        long getCount();
    }

    interface ServiceOpenCount {
        UUID getServiceId();

        Priority getPriority();

        long getCount();
    }

    interface ResolutionSample {
        Instant getSlaStartedAt();

        Instant getSlaResolvedAt();
    }
}
