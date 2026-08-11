package com.eventledger.gateway.api;

import com.eventledger.gateway.client.AccountServiceRejectedException;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.service.EventConflictException;
import com.eventledger.gateway.service.EventNotFoundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.tracing.Tracer;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final Tracer tracer;

    public GlobalExceptionHandler(Tracer tracer) {
        this.tracer = tracer;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException exception) {
        List<ErrorResponse.FieldErrorDetail> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldErrorDetail(error.getField(), error.getDefaultMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "The request contains invalid fields", details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> badRequest(Exception exception) {
        String message = exception instanceof HttpMessageNotReadableException
                ? "The request body is malformed or contains an unsupported value"
                : exception.getMessage();
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, List.of());
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ErrorResponse> eventNotFound(EventNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", exception.getMessage(), List.of());
    }

    @ExceptionHandler(EventConflictException.class)
    public ResponseEntity<ErrorResponse> eventConflict(EventConflictException exception) {
        return response(HttpStatus.CONFLICT, "EVENT_CONFLICT", exception.getMessage(), List.of());
    }

    @ExceptionHandler(AccountServiceRejectedException.class)
    public ResponseEntity<ErrorResponse> downstreamRejected(AccountServiceRejectedException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatus().value());
        HttpStatus resolved = status == null ? HttpStatus.BAD_GATEWAY : status;
        String code = resolved == HttpStatus.NOT_FOUND ? "ACCOUNT_NOT_FOUND" : "ACCOUNT_SERVICE_REJECTED";
        return response(resolved, code, exception.getMessage(), List.of());
    }

    @ExceptionHandler({AccountServiceUnavailableException.class, CallNotPermittedException.class})
    public ResponseEntity<ErrorResponse> downstreamUnavailable(RuntimeException exception) {
        log.atWarn().addKeyValue("exception", exception.getClass().getSimpleName())
                .log("Account Service unavailable");
        return response(HttpStatus.SERVICE_UNAVAILABLE, "ACCOUNT_SERVICE_UNAVAILABLE",
                "The Account Service is currently unreachable; retry later", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception exception) {
        log.error("Unexpected Event Gateway error", exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred", List.of());
    }

    private ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message,
                                                   List<ErrorResponse.FieldErrorDetail> details) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                code, message, traceId(), Instant.now(), details));
    }

    private String traceId() {
        return tracer.currentSpan() == null ? "" : tracer.currentSpan().context().traceId();
    }
}
