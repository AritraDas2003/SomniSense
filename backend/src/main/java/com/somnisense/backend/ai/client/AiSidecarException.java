package com.somnisense.backend.ai.client;

/**
 * Raised when communication with the internal Model C FastAPI sidecar fails, times out,
 * or returns an unexpected HTTP status or payload. Carries retryability classification for
 * the Stage 4 scheduler.
 */
public class AiSidecarException extends RuntimeException {

    private final Integer httpStatus;
    private final boolean retryable;

    public AiSidecarException(String message) {
        this(message, null, false);
    }

    public AiSidecarException(String message, Throwable cause) {
        this(message, cause, null, true);
    }

    public AiSidecarException(String message, boolean retryable) {
        this(message, null, retryable);
    }

    public AiSidecarException(String message, Integer httpStatus, boolean retryable) {
        super(message);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public AiSidecarException(String message, Throwable cause, Integer httpStatus, boolean retryable) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
