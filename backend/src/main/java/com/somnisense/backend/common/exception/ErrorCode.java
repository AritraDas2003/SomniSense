package com.somnisense.backend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Every error the API can return has one stable, machine-readable code.
 *
 * The frontend should branch on {@code code} (never on the human message, which may change).
 * Each code is tied to exactly one HTTP status and has a safe default message that never
 * reveals internal details such as SQL, class names or stack traces.
 */
public enum ErrorCode {

    // 400 - the client sent something wrong
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request has invalid fields"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Request is not valid"),
    SELF_ACTION_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "You cannot perform this action on your own account"),
    // 400, not 401: a wrong CURRENT password while logged in must not make the frontend log the user out.
    CURRENT_PASSWORD_INCORRECT(HttpStatus.BAD_REQUEST, "Current password is incorrect"),
    SOURCE_TYPE_MISMATCH(HttpStatus.BAD_REQUEST,
            "Device type must match the patient's source type (SIMULATED with SIMULATED, PHYSICAL with PHYSICAL)"),

    // 401 / 403 - who you are / what you may do
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    // Same message for wrong password, unknown user and deactivated account, so the response never
    // reveals whether an account exists (no "user enumeration").
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid username/email or password"),
    SESSION_INVALID(HttpStatus.UNAUTHORIZED, "Your session is no longer valid. Please log in again."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You do not have permission to perform this action"),
    CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN, "Missing or invalid CSRF token"),

    // 404 / 405 / 409 / 415
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported for this endpoint"),
    CONFLICT(HttpStatus.CONFLICT, "Request conflicts with the current state of the resource"),
    LAST_ACTIVE_ADMIN(HttpStatus.CONFLICT, "At least one active Admin must remain"),

    // Device assignments (Stage 7)
    DEVICE_NOT_AVAILABLE(HttpStatus.CONFLICT, "Device must be active and ENROLLED to be assigned"),
    PATIENT_NOT_ACTIVE(HttpStatus.CONFLICT, "Patient must be ACTIVE to be assigned a device"),
    DEVICE_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "Device is already assigned to another patient"),
    PATIENT_ALREADY_HAS_DEVICE(HttpStatus.CONFLICT, "Patient already has another device assigned"),
    DEVICE_IN_USE(HttpStatus.CONFLICT, "Device is assigned to a patient; release it first"),
    PATIENT_HAS_ACTIVE_DEVICE(HttpStatus.CONFLICT, "Patient has a device assigned; release it first"),

    // Monitoring sessions (Stage 8)
    NO_ACTIVE_ASSIGNMENT(HttpStatus.CONFLICT, "Patient has no device assigned; assign a device first"),
    ASSIGNMENT_MISMATCH(HttpStatus.CONFLICT, "The given device assignment is not the patient's current one; reload and try again"),
    SESSION_ALREADY_ACTIVE(HttpStatus.CONFLICT, "Patient already has an active monitoring session"),
    SESSION_ACTIVE(HttpStatus.CONFLICT, "A monitoring session is active on this assignment; end the session first"),
    INVALID_SESSION_TRANSITION(HttpStatus.CONFLICT, "Session is already finished and cannot be changed"),

    // Reading ingestion (Stage 9)
    NO_ACTIVE_SESSION(HttpStatus.CONFLICT, "Device has no active monitoring session; readings cannot be stored"),
    SESSION_MISMATCH(HttpStatus.CONFLICT, "sessionCode does not match the device's active monitoring session"),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "Request conflicts with existing data"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content type not supported; use application/json"),

    // Simulator control & fencing (Stage 4)
    SIMULATION_FENCING_REQUIRED(HttpStatus.CONFLICT, "Simulation ingestion requires simulationRunId and workerGeneration"),
    SIMULATION_MISMATCH(HttpStatus.CONFLICT, "simulationRunId does not match the session's active simulation run"),
    SIMULATION_NOT_RUNNING(HttpStatus.CONFLICT, "Simulation run is not RUNNING; readings rejected"),
    STALE_WORKER_GENERATION(HttpStatus.CONFLICT, "Stale simulator workerGeneration; stop orphan worker"),
    SIMULATION_LEASE_EXPIRED(HttpStatus.CONFLICT, "Simulation worker lease has expired; resume required"),
    SIMULATION_ALREADY_RUNNING(HttpStatus.CONFLICT, "Simulation run is already RUNNING for this session"),
    SIMULATION_INVALID_STATE(HttpStatus.CONFLICT, "Simulation run cannot perform this transition in its current state"),
    SIMULATION_DEVICE_NOT_SIMULATED(HttpStatus.CONFLICT, "Simulation control is only permitted for SIMULATED devices"),
    SIMULATION_WORKER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "Simulator worker could not be reached or rejected the command"),

    // 500 - our fault; details go to the server log only
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    /** Picks a sensible code for a plain HTTP status (used for Spring's built-in MVC errors). */
    public static ErrorCode fromStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> RESOURCE_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status >= 500 ? INTERNAL_ERROR : INVALID_REQUEST;
        };
    }
}
