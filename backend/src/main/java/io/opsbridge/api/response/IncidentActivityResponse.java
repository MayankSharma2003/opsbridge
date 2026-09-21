package io.opsbridge.api.response;

import io.opsbridge.domain.enums.ActivityEventType;
import java.time.Instant;
import java.util.UUID;

// eventType/fieldName/fromValue/toValue are raw -- frontend renders and localizes the sentence.
public record IncidentActivityResponse(
        UUID id,
        UserSummaryResponse actor,
        ActivityEventType eventType,
        String fieldName,
        String fromValue,
        String toValue,
        String comment,
        Instant createdAt) {
}
