package io.opsbridge.api.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Null fields are left unchanged. */
public record UpdateServiceRequest(
        @Size(max = 255) String name,
        String description,
        UUID ownerId,
        @Size(max = 255) String team,
        @Size(max = 255) String escalationContact,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal availabilityTarget) {
}
