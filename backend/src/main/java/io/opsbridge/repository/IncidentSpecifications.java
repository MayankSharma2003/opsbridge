package io.opsbridge.repository;

import io.opsbridge.domain.entity.Incident;
import io.opsbridge.service.command.IncidentSearchCommand;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

// Translates IncidentSearchCommand into a Specification -- query construction only, mirrors ServiceSpecifications.
public final class IncidentSpecifications {

    private IncidentSpecifications() {
    }

    public static Specification<Incident> matching(IncidentSearchCommand command) {
        Specification<Incident> spec = fetchAssociations();

        String q = command.q() == null ? null : command.q().trim();
        if (q != null && !q.isEmpty()) {
            spec = spec.and(keyword(q));
        }
        if (command.status() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), command.status()));
        }
        if (command.priority() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("priority"), command.priority()));
        }
        if (command.severity() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("severity"), command.severity()));
        }
        if (command.serviceId() != null) {
            UUID serviceId = command.serviceId();
            spec = spec.and((root, query, cb) -> cb.equal(root.get("service").get("id"), serviceId));
        }
        if (command.from() != null) {
            Instant fromInstant = command.from().atStartOfDay(ZoneOffset.UTC).toInstant();
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), fromInstant));
        }
        if (command.to() != null) {
            // Exclusive bound one day later, so "to" includes the whole named day.
            Instant toInstant = command.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            spec = spec.and((root, query, cb) -> cb.lessThan(root.get("createdAt"), toInstant));
        }
        return spec;
    }

    // Specification queries bypass @EntityGraph on findAll(Pageable); without this fetch, DTO mapping after the tx closes hits a lazy proxy.
    // Skipped on the separate COUNT query Spring Data runs for pagination.
    private static Specification<Incident> fetchAssociations() {
        return (root, query, cb) -> {
            if (Long.class != query.getResultType() && long.class != query.getResultType()) {
                root.fetch("service", JoinType.LEFT);
                root.fetch("reporter", JoinType.LEFT);
                root.fetch("assignee", JoinType.LEFT);
                query.distinct(true);
            }
            return cb.conjunction();
        };
    }

    private static Specification<Incident> keyword(String q) {
        String pattern = "%" + q.toLowerCase() + "%";
        return (root, query, cb) -> {
            Predicate title = cb.like(cb.lower(root.get("title")), pattern);
            Predicate description = cb.like(cb.lower(root.get("description")), pattern);
            Predicate publicKey = cb.like(cb.lower(root.get("publicKey")), pattern);
            return cb.or(title, description, publicKey);
        };
    }
}
