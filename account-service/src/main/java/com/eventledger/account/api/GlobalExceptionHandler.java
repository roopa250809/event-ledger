package com.eventledger.account.api;

import com.eventledger.account.service.AccountNotFoundException;
import com.eventledger.account.service.CurrencyConflictException;
import com.eventledger.account.service.EventConflictException;
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
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", safeMessage(exception), List.of());
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(AccountNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", exception.getMessage(), List.of());
    }

    @ExceptionHandler({EventConflictException.class, CurrencyConflictException.class})
    public ResponseEntity<ErrorResponse> conflict(RuntimeException exception) {
        return response(HttpStatus.CONFLICT, "TRANSACTION_CONFLICT", exception.getMessage(), List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception exception) {
        log.error("Unexpected Account Service error", exception);
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

    private static String safeMessage(Exception exception) {
        if (exception instanceof HttpMessageNotReadableException) {
            return "The request body is malformed or contains an unsupported value";
        }
        return exception.getMessage() == null ? "Invalid request" : exception.getMessage();
    }
}
