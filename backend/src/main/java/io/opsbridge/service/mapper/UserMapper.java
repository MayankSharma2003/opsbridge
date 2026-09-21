package io.opsbridge.service.mapper;

import io.opsbridge.api.response.UserSummaryResponse;
import io.opsbridge.domain.entity.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserSummaryResponse toSummary(User user) {
        if (user == null) {
            return null;
        }
        return new UserSummaryResponse(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }
}
