package io.opsbridge.domain.entity;

import io.opsbridge.domain.enums.ActivityEntityType;
import io.opsbridge.domain.enums.ActivityEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

// Immutable (no updatedAt), unlike AuditableEntity. Targets exactly one of incident/service per the
// V5 CHECK constraint -- static factories enforce that, not a constructor.
// createdAt comes from the caller's Clock, not @PrePersist, so it shares a time source with SLA math and stays deterministic in tests.
@Entity
@Table(name = "incident_activity")
public class IncidentActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false)
    private ActivityEntityType entityType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id")
    private Incident incident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id")
    private Service service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id", nullable = false)
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private ActivityEventType eventType;

    @Column(name = "field_name")
    private String fieldName;

    @Column(name = "from_value")
    private String fromValue;

    @Column(name = "to_value")
    private String toValue;

    @Column
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected IncidentActivity() {
    }

    private IncidentActivity(
            ActivityEntityType entityType,
            Incident incident,
            Service service,
            User actor,
            ActivityEventType eventType,
            String fieldName,
            String fromValue,
            String toValue,
            String comment,
            Instant createdAt) {
        this.entityType = entityType;
        this.incident = incident;
        this.service = service;
        this.actor = actor;
        this.eventType = eventType;
        this.fieldName = fieldName;
        this.fromValue = fromValue;
        this.toValue = toValue;
        this.comment = comment;
        this.createdAt = createdAt;
    }

    public static IncidentActivity forIncident(
            Incident incident,
            User actor,
            ActivityEventType eventType,
            String fieldName,
            String fromValue,
            String toValue,
            String comment,
            Instant createdAt) {
        return new IncidentActivity(
                ActivityEntityType.INCIDENT,
                incident,
                null,
                actor,
                eventType,
                fieldName,
                fromValue,
                toValue,
                comment,
                createdAt);
    }

    public static IncidentActivity forService(
            Service service,
            User actor,
            ActivityEventType eventType,
            String fieldName,
            String fromValue,
            String toValue,
            String comment,
            Instant createdAt) {
        return new IncidentActivity(
                ActivityEntityType.SERVICE,
                null,
                service,
                actor,
                eventType,
                fieldName,
                fromValue,
                toValue,
                comment,
                createdAt);
    }

    public UUID getId() {
        return id;
    }

    public ActivityEntityType getEntityType() {
        return entityType;
    }

    public Incident getIncident() {
        return incident;
    }

    public Service getService() {
        return service;
    }

    public User getActor() {
        return actor;
    }

    public ActivityEventType getEventType() {
        return eventType;
    }

    public String getFieldName() {
        return fieldName;
    }

    public String getFromValue() {
        return fromValue;
    }

    public String getToValue() {
        return toValue;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
