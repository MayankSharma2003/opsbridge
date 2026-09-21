package io.opsbridge.support.error;

import io.opsbridge.domain.enums.IncidentStatus;
import org.springframework.http.HttpStatus;

// 422 not 409 -- spec reserves 409 for version conflicts; an illegal transition is workflow
// rejection of a well-formed request, not a write race.
public class IllegalStatusTransitionException extends ApiException {

    public IllegalStatusTransitionException(IncidentStatus from, IncidentStatus to) {
        super(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "ILLEGAL_STATUS_TRANSITION",
                "Cannot transition an incident from %s to %s.".formatted(from, to));
    }
}
