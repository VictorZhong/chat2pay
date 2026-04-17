package com.chat2pay.app.api;

import com.chat2pay.app.common.ApiException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiModels.ErrorResponse> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiModels.ErrorResponse(
                        exception.getCode(),
                        exception.getMessage(),
                        List.of(),
                        Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiModels.ErrorResponse> handleValidationException(MethodArgumentNotValidException exception) {
        List<String> details = exception.getBindingResult().getAllErrors().stream()
                .map(error -> error instanceof FieldError fieldError
                        ? fieldError.getField() + ": " + fieldError.getDefaultMessage()
                        : error.getDefaultMessage())
                .toList();

        return ResponseEntity.badRequest()
                .body(new ApiModels.ErrorResponse("CHAT2PAY-400", "Invalid request payload.", details, Instant.now()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiModels.ErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiModels.ErrorResponse(
                        "CHAT2PAY-400",
                        "Invalid request payload.",
                        exception.getConstraintViolations().stream()
                                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                                .toList(),
                        Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiModels.ErrorResponse> handleUnexpected(Exception exception) {
        log.error("Unhandled application exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiModels.ErrorResponse(
                        "CHAT2PAY-500",
                        "Unexpected server error.",
                        List.of(exception.getClass().getSimpleName()),
                        Instant.now()));
    }
}
