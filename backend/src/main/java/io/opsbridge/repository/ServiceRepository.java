package io.opsbridge.repository;

import io.opsbridge.domain.entity.Service;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

// JpaSpecificationExecutor backs the catalog's filtered/search list (see ServiceSpecifications).
// list() always goes through findAll(Specification, Pageable), even unfiltered, since the eager owner fetch moved there from @EntityGraph.
public interface ServiceRepository extends JpaRepository<Service, UUID>, JpaSpecificationExecutor<Service> {

    // Owner eager-fetched, same reason as IncidentRepository: open-in-view is off.
    @Override
    @EntityGraph(attributePaths = "owner")
    Optional<Service> findById(UUID id);
}
