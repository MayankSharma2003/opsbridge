package io.opsbridge.service.mapper;

import io.opsbridge.api.response.IncidentResponse;
import io.opsbridge.api.response.IncidentSlaResponse;
import io.opsbridge.api.response.IncidentSummaryResponse;
import io.opsbridge.domain.entity.Incident;
import org.springframework.stereotype.Component;

@Component
public class IncidentMapper {

    private final ServiceMapper serviceMapper;
    private final UserMapper userMapper;

    public IncidentMapper(ServiceMapper serviceMapper, UserMapper userMapper) {
        this.serviceMapper = serviceMapper;
        this.userMapper = userMapper;
    }

    public IncidentResponse toResponse(Incident incident) {
        return new IncidentResponse(
                incident.getId(),
                incident.getPublicKey(),
                incident.getTitle(),
                incident.getDescription(),
                serviceMapper.toSummary(incident.getService()),
                userMapper.toSummary(incident.getReporter()),
                userMapper.toSummary(incident.getAssignee()),
                incident.getPriority(),
                incident.getSeverity(),
                incident.getStatus(),
                toSlaResponse(incident),
                incident.getResolutionNotes(),
                incident.getVersion(),
                incident.getCreatedAt(),
                incident.getUpdatedAt());
    }

    public IncidentSummaryResponse toSummary(Incident incident) {
        return new IncidentSummaryResponse(
                incident.getId(),
                incident.getPublicKey(),
                incident.getTitle(),
                serviceMapper.toSummary(incident.getService()),
                userMapper.toSummary(incident.getAssignee()),
                incident.getPriority(),
                incident.getSeverity(),
                incident.getStatus(),
                incident.getSlaState(),
                incident.getSlaResolutionTargetAt(),
                incident.getVersion(),
                incident.getCreatedAt(),
                incident.getUpdatedAt());
    }

    private IncidentSlaResponse toSlaResponse(Incident incident) {
        return new IncidentSlaResponse(
                incident.getSlaState(),
                incident.getSlaStartedAt(),
                incident.getSlaResponseTargetAt(),
                incident.getSlaMitigationTargetAt(),
                incident.getSlaResolutionTargetAt(),
                incident.getSlaRespondedAt(),
                incident.getSlaMitigatedAt(),
                incident.getSlaResolvedAt(),
                incident.getSlaBreachedAt());
    }
}
