package com.somnisense.backend.simulation.client;

/** Raised when communication with the internal Python simulator-worker fails. */
public class SimulationWorkerException extends RuntimeException {

    public SimulationWorkerException(String message) {
        super(message);
    }

    public SimulationWorkerException(String message, Throwable cause) {
        super(message, cause);
    }
}
