package com.somnisense.backend.ai.client;

/**
 * Raised when communication with the internal Model C FastAPI sidecar fails, times out,
 * or returns an unexpected HTTP status or payload.
 */
public class AiSidecarException extends RuntimeException {

    public AiSidecarException(String message) {
        super(message);
    }

    public AiSidecarException(String message, Throwable cause) {
        super(message, cause);
    }
}
