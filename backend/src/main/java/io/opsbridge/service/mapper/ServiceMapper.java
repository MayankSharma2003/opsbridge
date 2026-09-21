package io.opsbridge.service.mapper;

import io.opsbridge.api.response.ServiceResponse;
import io.opsbridge.api.response.ServiceSummaryResponse;
import io.opsbridge.domain.entity.Service;
import io.opsbridge.service.ServiceWithOpenIncidents;
import org.springframework.stereotype.Component;

@Component
public class ServiceMapper {

    private final UserMapper userMapper;

    public ServiceMapper(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    // Takes the pair, not a bare Service, on purpose -- an entity has no open-incident count, and an
    // earlier Service-only overload that defaulted it to zero silently reported edited services as
    // having none.
    public ServiceResponse toResponse(ServiceWithOpenIncidents pair) {
        return toResponse(pair.service(), pair.openIncidentCount(), pair.urgentOpenIncidentCount());
    }

    private ServiceResponse toResponse(Service service, long openIncidentCount, long urgentOpenIncidentCount) {
        return new ServiceResponse(
                service.getId(),
                service.getName(),
                service.getDescription(),
                userMapper.toSummary(service.getOwner()),
                service.getTeam(),
                service.getEscalationContact(),
                service.getAvailabilityTarget(),
                service.getStatus(),
                openIncidentCount,
                urgentOpenIncidentCount,
                service.getCreatedAt(),
                service.getUpdatedAt());
    }

    public ServiceSummaryResponse toSummary(Service service) {
        if (service == null) {
            return null;
        }
        return new ServiceSummaryResponse(service.getId(), service.getName());
    }
}
