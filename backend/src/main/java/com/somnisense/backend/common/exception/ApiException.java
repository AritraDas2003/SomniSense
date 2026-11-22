package com.somnisense.backend.common.exception;

/**
 * Base class for errors that our own services throw on purpose ("expected" errors).
 *
 * It extends RuntimeException (an "unchecked" exception), so service methods do not need
 * "throws" clauses. GlobalExceptionHandler catches it and turns it into an ApiError response
 * using the ErrorCode's HTTP status.
 *
 * IMPORTANT: the message is sent to the client, so never put passwords, SQL or
 * sensitive patient data in it.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.getDefaultMessage());
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
