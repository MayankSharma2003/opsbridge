package io.opsbridge.service.mapper;

import io.opsbridge.api.response.IncidentActivityResponse;
import io.opsbridge.domain.entity.IncidentActivity;
import org.springframework.stereotype.Component;

@Component
public class IncidentActivityMapper {

    private final UserMapper userMapper;

    public IncidentActivityMapper(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public IncidentActivityResponse toResponse(IncidentActivity activity) {
        return new IncidentActivityResponse(
                activity.getId(),
                userMapper.toSummary(activity.getActor()),
                activity.getEventType(),
                activity.getFieldName(),
                activity.getFromValue(),
                activity.getToValue(),
                activity.getComment(),
                activity.getCreatedAt());
    }
}
