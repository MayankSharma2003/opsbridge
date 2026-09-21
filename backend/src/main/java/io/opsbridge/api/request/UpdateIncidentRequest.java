package io.opsbridge.api.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Null fields are left unchanged. */
public record UpdateIncidentRequest(
        @NotNull Integer expectedVersion,
        @Size(max = 255) String title,
        String description,
        UUID serviceId,
        String resolutionNotes) {
}
