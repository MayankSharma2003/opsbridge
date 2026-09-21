package io.opsbridge.support.error;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    // Bad enum/type in a query param (e.g. ?status=NOPE) is caller error, not a 500 -- without this
    // handler it falls through to the catch-all below.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = "%s: expected %s"
                .formatted(
                        ex.getName(),
                        ex.getRequiredType() == null ? "a valid value" : ex.getRequiredType().getSimpleName());
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    // @PreAuthorize denials come through the method interceptor, not the filter chain's
    // AccessDeniedHandler, so without this they'd fall through to a 500. Mirrors
    // RestSecurityResponses' shape so callers see one contract.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        return build(
                HttpStatus.FORBIDDEN,
                "FORBIDDEN",
                "The current role cannot perform this operation.",
                request);
    }

    // True concurrent-write race (two txns read the same version, second commit loses) surfaced the
    // same as an explicit stale-version check, so callers have one conflict contract.
    // Catches ConcurrencyFailureException, not just its optimistic-locking subclass -- Hibernate's
    // version-checked UPDATE raises ObjectOptimisticLockingFailureException, but a DB-detected
    // write-write conflict (Postgres serialization failure, H2's concurrent-update error) is a
    // sibling class; both mean 409, not 500.
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            ConcurrencyFailureException ex, HttpServletRequest request) {
        StaleVersionException translated = new StaleVersionException();
        return build(translated.getStatus(), translated.getCode(), translated.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.", request);
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(clock),
                status.value(),
                code,
                message,
                request.getRequestURI(),
                MDC.get(TraceIdFilter.TRACE_ID_KEY));
        return ResponseEntity.status(status).body(body);
    }
}
