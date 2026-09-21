package io.opsbridge.domain.entity;

import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "sla_policies")
public class SlaPolicy extends AuditableEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    // Null severity means "any severity" for this priority -- see V3 seed.
    @Enumerated(EnumType.STRING)
    @Column
    private Severity severity;

    @Column(name = "response_target_minutes", nullable = false)
    private int responseTargetMinutes;

    @Column(name = "mitigation_target_minutes", nullable = false)
    private int mitigationTargetMinutes;

    @Column(name = "resolution_target_minutes", nullable = false)
    private int resolutionTargetMinutes;

    @Column(nullable = false)
    private boolean active = true;

    protected SlaPolicy() {
    }

    public SlaPolicy(
            Priority priority,
            Severity severity,
            int responseTargetMinutes,
            int mitigationTargetMinutes,
            int resolutionTargetMinutes) {
        this.priority = priority;
        this.severity = severity;
        this.responseTargetMinutes = responseTargetMinutes;
        this.mitigationTargetMinutes = mitigationTargetMinutes;
        this.resolutionTargetMinutes = resolutionTargetMinutes;
        this.active = true;
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

    public int getResponseTargetMinutes() {
        return responseTargetMinutes;
    }

    public void setResponseTargetMinutes(int responseTargetMinutes) {
        this.responseTargetMinutes = responseTargetMinutes;
    }

    public int getMitigationTargetMinutes() {
        return mitigationTargetMinutes;
    }

    public void setMitigationTargetMinutes(int mitigationTargetMinutes) {
        this.mitigationTargetMinutes = mitigationTargetMinutes;
    }

    public int getResolutionTargetMinutes() {
        return resolutionTargetMinutes;
    }

    public void setResolutionTargetMinutes(int resolutionTargetMinutes) {
        this.resolutionTargetMinutes = resolutionTargetMinutes;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
