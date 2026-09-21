package io.opsbridge.api.response;

import io.opsbridge.domain.enums.Role;
import java.time.Instant;
import java.util.UUID;

public record LoginResponse(
        String token,
        String tokenType,
        Instant expiresAt,
        UUID userId,
        String name,
        String email,
        Role role) {
}
