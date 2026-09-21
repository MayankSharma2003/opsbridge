package io.opsbridge.support.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.opsbridge.domain.enums.IncidentStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

// Asserts the one-shape error contract directly, including branches (access denied, an
// optimistic-lock race, the catch-all) that are awkward to provoke through a real request.
class GlobalExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));

    private HttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void apiException_keepsItsOwnStatusAndCode() {
        var response = handler.handleApiException(new ResourceNotFoundException("Incident", UUID.randomUUID()), request("/api/v1/incidents/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().code()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void everyResponseCarriesTheFullShape() {
        var body = handler.handleApiException(new StaleVersionException(), request("/api/v1/incidents/x/status")).getBody();

        assertThat(body.timestamp()).isEqualTo(NOW);
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.code()).isEqualTo("STALE_VERSION");
        assertThat(body.message()).isNotBlank();
        assertThat(body.path()).isEqualTo("/api/v1/incidents/x/status");
    }

    @Test
    void staleVersion_is409SoTheClientKnowsToReload() {
        var response = handler.handleApiException(new StaleVersionException(), request("/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void illegalTransition_is422NotAGenericBadRequest() {
        var response = handler.handleApiException(
                new IllegalStatusTransitionException(IncidentStatus.RESOLVED, IncidentStatus.OPEN), request("/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().code()).isEqualTo("ILLEGAL_STATUS_TRANSITION");
    }

    @Test
    void bodyValidation_namesTheOffendingFields() throws Exception {
        BindingResult binding = new BeanPropertyBindingResult(new Object(), "createIncidentRequest");
        binding.rejectValue(null, "NotBlank", "must not be blank");
        binding.addError(new org.springframework.validation.FieldError(
                "createIncidentRequest", "title", "must not be blank"));
        var exception = new MethodArgumentNotValidException(
                new MethodParameter(this.getClass().getDeclaredMethod("bodyValidation_namesTheOffendingFields"), -1),
                binding);

        var response = handler.handleValidation(exception, request("/api/v1/incidents"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().message()).contains("title");
    }

    @Test
    void anUnconvertibleQueryParamIsCallerErrorNotAServerError() {
        var exception = new MethodArgumentTypeMismatchException(
                "NOPE", IncidentStatus.class, "status", null, new IllegalArgumentException("bad"));

        var response = handler.handleTypeMismatch(exception, request("/api/v1/incidents"));

        // Without this branch it falls through to the catch-all and reads as a 500.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().message()).contains("status");
    }

    @Test
    void aPreAuthorizeDenialIs403NotA500() {
        var response = handler.handleAccessDenied(new AccessDeniedException("denied"), request("/api/v1/services"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void aConcurrentWriteRaceSurfacesAsTheSameConflictAsAnExplicitStaleVersion() {
        var response = handler.handleOptimisticLock(new OptimisticLockingFailureException("race"), request("/x"));

        // One conflict contract for callers, whichever route the race arrived by.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("STALE_VERSION");
    }

    @Test
    void anUnexpectedExceptionNeverLeaksItsInternals() {
        var response = handler.handleUnexpected(
                new IllegalStateException("connection string user=admin password=hunter2"), request("/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred.");
        assertThat(response.getBody().message()).doesNotContain("hunter2");
    }

    @Test
    void theTraceIdIsCarriedThroughWhenOneIsInScope() {
        MDC.put(TraceIdFilter.TRACE_ID_KEY, "trace-123");

        var body = handler.handleApiException(new StaleVersionException(), request("/x")).getBody();

        assertThat(body.traceId()).isEqualTo("trace-123");
    }
}
