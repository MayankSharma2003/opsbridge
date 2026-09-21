package io.opsbridge.api.response;

import io.opsbridge.domain.enums.Role;
import java.util.UUID;

public record UserSummaryResponse(UUID id, String name, String email, Role role) {
}
