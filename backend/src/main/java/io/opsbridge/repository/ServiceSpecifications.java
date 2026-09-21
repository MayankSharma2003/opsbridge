package io.opsbridge.repository;

import io.opsbridge.domain.entity.Service;
import io.opsbridge.service.command.ServiceSearchCommand;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

// Translates ServiceSearchCommand into a Specification -- persistence plumbing only, like the @Query methods on IncidentRepository.
public final class ServiceSpecifications {

    private ServiceSpecifications() {
    }

    public static Specification<Service> matching(ServiceSearchCommand command) {
        Specification<Service> spec = fetchOwner();

        String q = command.q() == null ? null : command.q().trim();
        if (q != null && !q.isEmpty()) {
            spec = spec.and(keyword(q));
        }
        if (command.status() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), command.status()));
        }
        if (command.team() != null && !command.team().isBlank()) {
            String team = command.team().trim().toLowerCase();
            spec = spec.and((root, query, cb) -> cb.equal(cb.lower(root.get("team")), team));
        }
        return spec;
    }

    // Bypasses @EntityGraph the way Specification queries do; without it, DTO mapping after the tx closes hits a lazy owner proxy.
    // Result-type check skips the fetch on the separate COUNT query, which can't carry a fetch join.
    private static Specification<Service> fetchOwner() {
        return (root, query, cb) -> {
            if (Long.class != query.getResultType() && long.class != query.getResultType()) {
                root.fetch("owner", JoinType.LEFT);
                query.distinct(true);
            }
            return cb.conjunction();
        };
    }

    private static Specification<Service> keyword(String q) {
        String pattern = "%" + q.toLowerCase() + "%";
        return (root, query, cb) -> {
            Predicate name = cb.like(cb.lower(root.get("name")), pattern);
            Predicate description = cb.like(cb.lower(root.get("description")), pattern);
            Predicate team = cb.like(cb.lower(root.get("team")), pattern);
            return cb.or(name, description, team);
        };
    }
}
