package io.opsbridge.security;

import io.opsbridge.domain.enums.Role;
import java.util.UUID;

// Authenticated caller rebuilt from verified JWT claims; carries userId so controllers can name the actor without a DB lookup.
public record OpsBridgePrincipal(UUID userId, String email, String name, Role role) {
}
