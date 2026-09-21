package io.opsbridge.api.response;

import io.opsbridge.domain.enums.Role;
import java.util.UUID;

public record MeResponse(UUID id, String name, String email, Role role) {
}
