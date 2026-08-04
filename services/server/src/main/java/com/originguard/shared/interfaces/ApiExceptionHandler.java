package com.originguard.shared.interfaces;

import com.originguard.identity.application.InvalidCredentialsException;
import com.originguard.identity.application.InvalidRefreshTokenException;
import com.originguard.shared.application.BusinessConflictException;
import com.originguard.shared.application.ResourceNotFoundException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({InvalidCredentialsException.class, InvalidRefreshTokenException.class})
    ResponseEntity<ApiError> unauthorized(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("AUTHENTICATION_FAILED", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("FORBIDDEN", "Permission denied", Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        return ResponseEntity.unprocessableEntity()
                .body(new ApiError("VALIDATION_FAILED", "Request validation failed", Instant.now()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformedRequest(HttpMessageNotReadableException exception) {
        return ResponseEntity.unprocessableEntity()
                .body(new ApiError("VALIDATION_FAILED", "Request body is malformed or contains an invalid value", Instant.now()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(exception.code(), exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(BusinessConflictException.class)
    ResponseEntity<ApiError> conflict(BusinessConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(exception.code(), exception.getMessage(), Instant.now()));
    }

    public record ApiError(String code, String message, Instant timestamp) {}
}
