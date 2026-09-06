package com.vid2knowledge.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import com.vid2knowledge.common.api.CorrelationIdFilter;
import com.vid2knowledge.usage.application.EntitlementNotFoundException;
import com.vid2knowledge.usage.application.IdempotencyConflictException;
import com.vid2knowledge.usage.domain.QuotaExceededException;
import com.vid2knowledge.analysis.application.SourceRightsRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.UUID;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<ApiError.FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiError.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", fieldErrors, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        List<ApiError.FieldError> fieldErrors = exception.getConstraintViolations().stream()
                .map(error -> new ApiError.FieldError(error.getPropertyPath().toString(), error.getMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", fieldErrors, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(
            NoResourceFoundException exception,
            HttpServletRequest request
    ) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found", List.of(), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(
            ResponseStatusException exception,
            HttpServletRequest request
    ) {
        HttpStatusCode status = exception.getStatusCode();
        String message = exception.getReason() == null || exception.getReason().isBlank()
                ? defaultMessage(status)
                : exception.getReason();

        return response(status, "REQUEST_REJECTED", message, List.of(), request);
    }

    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ApiError> handleQuotaExceeded(
            QuotaExceededException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.TOO_MANY_REQUESTS,
                "USAGE_QUOTA_EXCEEDED",
                "Usage allowance is insufficient for this request",
                List.of(),
                request
        );
    }

    @ExceptionHandler(EntitlementNotFoundException.class)
    public ResponseEntity<ApiError> handleMissingEntitlement(
            EntitlementNotFoundException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.PAYMENT_REQUIRED,
                "ENTITLEMENT_REQUIRED",
                "An active entitlement is required",
                List.of(),
                request
        );
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(
            IdempotencyConflictException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                exception.getMessage(),
                List.of(),
                request
        );
    }

    @ExceptionHandler(SourceRightsRequiredException.class)
    public ResponseEntity<ApiError> handleSourceRightsRequired(
            SourceRightsRequiredException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.NOT_FOUND,
                "SOURCE_NOT_FOUND_OR_RIGHTS_REQUIRED",
                exception.getMessage(),
                List.of(),
                request
        );
    }

    @ExceptionHandler
    public ResponseEntity<ApiError> handleInvalidYoutubeUrl(
            InvalidYoutubeUrlException exception,
            HttpServletRequest request
    ) {
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_YOUTUBE_URL",
                exception.getMessage(),
                List.of(),
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        String errorId = UUID.randomUUID().toString();
        log.error("Unhandled request error. errorId={}, method={}, path={}, exceptionType={}",
                errorId,
                request.getMethod(),
                request.getRequestURI(),
                exception.getClass().getSimpleName());

        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred. Reference: " + errorId,
                List.of(),
                request
        );
    }

    private ResponseEntity<ApiError> response(
            HttpStatusCode status,
            String code,
            String message,
            List<ApiError.FieldError> fieldErrors,
            HttpServletRequest request
    ) {
        Object correlationAttribute = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        String correlationId = correlationAttribute == null ? "unknown" : correlationAttribute.toString();
        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                code,
                message,
                request.getRequestURI(),
                correlationId,
                fieldErrors
        );
        return ResponseEntity.status(status).body(body);
    }

    private String defaultMessage(HttpStatusCode status) {
        HttpStatus resolvedStatus = HttpStatus.resolve(status.value());
        return resolvedStatus == null ? "Request failed" : resolvedStatus.getReasonPhrase();
    }
}
