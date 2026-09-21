package io.opsbridge.api.request;

import io.opsbridge.domain.enums.Priority;
import io.opsbridge.domain.enums.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

// Reporter comes from the authenticated caller, not the request body.
public record CreateIncidentRequest(
        @NotBlank @Size(max = 255) String title,
        String description,
        @NotNull UUID serviceId,
        @NotNull Priority priority,
        @NotNull Severity severity) {
}
