package com.somnisense.backend.common.response;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.somnisense.backend.common.exception.ErrorCode;

import java.time.Instant;
import java.util.List;

/**
 * The ONE JSON shape every error response uses (successful responses return plain data instead).
 *
 * <pre>
 * {
 *   "timestamp": "2026-11-21T12:40:00Z",   // UTC, ISO-8601
 *   "status": 400,                          // same as the HTTP status
 *   "error": "Bad Request",                 // HTTP reason phrase
 *   "code": "VALIDATION_FAILED",            // stable ErrorCode - frontend branches on this
 *   "message": "Request has invalid fields",// human readable, safe to display
 *   "path": "/api/v1/patients",             // request path, without the query string
 *   "correlationId": "3c9e...",             // matches the X-Correlation-Id response header and server logs
 *   "fieldErrors": []                       // always present; filled only for VALIDATION_FAILED
 * }
 * </pre>
 *
 * The class is immutable: all fields are final and set once in the constructor.
 * {@code @JsonPropertyOrder} fixes the order of the JSON fields so responses are easy to read.
 */
@JsonPropertyOrder({"timestamp", "status", "error", "code", "message", "path", "correlationId", "fieldErrors"})
public class ApiError {

    private final Instant timestamp;
    private final int status;
    private final String error;
    private final String code;
    private final String message;
    private final String path;
    private final String correlationId;
    private final List<FieldErrorDetail> fieldErrors;

    public ApiError(ErrorCode errorCode, String message, String path, String correlationId,
                    List<FieldErrorDetail> fieldErrors) {
        this.timestamp = Instant.now();
        this.status = errorCode.getStatus().value();
        this.error = errorCode.getStatus().getReasonPhrase();
        this.code = errorCode.name();
        this.message = message;
        this.path = path;
        this.correlationId = correlationId;
        // List.copyOf makes an unmodifiable copy; null becomes an empty list so "fieldErrors" is always an array.
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getPath() {
        return path;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public List<FieldErrorDetail> getFieldErrors() {
        return fieldErrors;
    }
}
