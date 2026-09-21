package io.opsbridge.api.response;

import io.opsbridge.domain.enums.Priority;

// raw enum; frontend localizes the label
public record PriorityCountResponse(Priority priority, long count) {
}
