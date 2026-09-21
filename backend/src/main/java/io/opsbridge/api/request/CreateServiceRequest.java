package io.opsbridge.api.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateServiceRequest(
        @NotBlank @Size(max = 255) String name,
        String description,
        @NotNull UUID ownerId,
        @Size(max = 255) String team,
        @Size(max = 255) String escalationContact,
        @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal availabilityTarget) {
}
