package io.opsbridge.domain.entity;

import io.opsbridge.domain.enums.IncidentStatus;
import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Severity;
import io.opsbridge.domain.enums.SlaState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "incidents")
public class Incident extends AuditableEntity {

    @Column(name = "public_key", nullable = false, unique = true, updatable = false)
    private String publicKey;

    @Column(nullable = false)
    private String title;

    @Column
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status = IncidentStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sla_policy_id")
    private SlaPolicy slaPolicy;

    /** Clock-controlled SLA start; all SLA targets are measured from this instant. */
    @Column(name = "sla_started_at")
    private Instant slaStartedAt;

    @Column(name = "sla_response_target_at")
    private Instant slaResponseTargetAt;

    @Column(name = "sla_mitigation_target_at")
    private Instant slaMitigationTargetAt;

    @Column(name = "sla_resolution_target_at")
    private Instant slaResolutionTargetAt;

    @Column(name = "sla_responded_at")
    private Instant slaRespondedAt;

    @Column(name = "sla_mitigated_at")
    private Instant slaMitigatedAt;

    @Column(name = "sla_resolved_at")
    private Instant slaResolvedAt;

    @Column(name = "sla_breached_at")
    private Instant slaBreachedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "sla_state", nullable = false)
    private SlaState slaState = SlaState.ON_TRACK;

    @Column(name = "resolution_notes")
    private String resolutionNotes;

    @Version
    @Column(nullable = false)
    private int version;

    protected Incident() {
    }

    public Incident(
            String publicKey,
            String title,
            String description,
            Service service,
            User reporter,
            Priority priority,
            Severity severity) {
        this.publicKey = publicKey;
        this.title = title;
        this.description = description;
        this.service = service;
        this.reporter = reporter;
        this.priority = priority;
        this.severity = severity;
        this.status = IncidentStatus.OPEN;
        this.slaState = SlaState.ON_TRACK;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Service getService() {
        return service;
    }

    public void setService(Service service) {
        this.service = service;
    }

    public User getReporter() {
        return reporter;
    }

    public User getAssignee() {
        return assignee;
    }

    public void setAssignee(User assignee) {
        this.assignee = assignee;
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public void setStatus(IncidentStatus status) {
        this.status = status;
    }

    public SlaPolicy getSlaPolicy() {
        return slaPolicy;
    }

    public void setSlaPolicy(SlaPolicy slaPolicy) {
        this.slaPolicy = slaPolicy;
    }

    public Instant getSlaStartedAt() {
        return slaStartedAt;
    }

    public void setSlaStartedAt(Instant slaStartedAt) {
        this.slaStartedAt = slaStartedAt;
    }

    public Instant getSlaResponseTargetAt() {
        return slaResponseTargetAt;
    }

    public void setSlaResponseTargetAt(Instant slaResponseTargetAt) {
        this.slaResponseTargetAt = slaResponseTargetAt;
    }

    public Instant getSlaMitigationTargetAt() {
        return slaMitigationTargetAt;
    }

    public void setSlaMitigationTargetAt(Instant slaMitigationTargetAt) {
        this.slaMitigationTargetAt = slaMitigationTargetAt;
    }

    public Instant getSlaResolutionTargetAt() {
        return slaResolutionTargetAt;
    }

    public void setSlaResolutionTargetAt(Instant slaResolutionTargetAt) {
        this.slaResolutionTargetAt = slaResolutionTargetAt;
    }

    public Instant getSlaRespondedAt() {
        return slaRespondedAt;
    }

    public void setSlaRespondedAt(Instant slaRespondedAt) {
        this.slaRespondedAt = slaRespondedAt;
    }

    public Instant getSlaMitigatedAt() {
        return slaMitigatedAt;
    }

    public void setSlaMitigatedAt(Instant slaMitigatedAt) {
        this.slaMitigatedAt = slaMitigatedAt;
    }

    public Instant getSlaResolvedAt() {
        return slaResolvedAt;
    }

    public void setSlaResolvedAt(Instant slaResolvedAt) {
        this.slaResolvedAt = slaResolvedAt;
    }

    public Instant getSlaBreachedAt() {
        return slaBreachedAt;
    }

    public void setSlaBreachedAt(Instant slaBreachedAt) {
        this.slaBreachedAt = slaBreachedAt;
    }

    public SlaState getSlaState() {
        return slaState;
    }

    public void setSlaState(SlaState slaState) {
        this.slaState = slaState;
    }

    public String getResolutionNotes() {
        return resolutionNotes;
    }

    public void setResolutionNotes(String resolutionNotes) {
        this.resolutionNotes = resolutionNotes;
    }

    public int getVersion() {
        return version;
    }
}
