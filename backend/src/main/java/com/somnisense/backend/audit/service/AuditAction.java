package com.somnisense.backend.audit.service;

/**
 * The fixed list of audit action names (stored in audit_logs.action).
 * Using constants avoids typos like "LOGIN_SUCESS" scattered through the code.
 * New actions are added here as later stages need them.
 */
public final class AuditAction {

    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String ADMIN_BOOTSTRAPPED = "ADMIN_BOOTSTRAPPED";
    public static final String SESSION_REVOKED = "SESSION_REVOKED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";

    // Staff management (Stage 4) - user_id = the Admin who acted, entity_id = the affected account
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_ROLE_CHANGED = "USER_ROLE_CHANGED";
    public static final String USER_DEACTIVATED = "USER_DEACTIVATED";
    public static final String USER_REACTIVATED = "USER_REACTIVATED";
    public static final String USER_PASSWORD_RESET = "USER_PASSWORD_RESET";

    // Patients (Stage 5) - details hold field NAMES only, never names, dates of birth or medical text
    public static final String PATIENT_CREATED = "PATIENT_CREATED";
    public static final String PATIENT_UPDATED = "PATIENT_UPDATED";
    public static final String PATIENT_CONTACT_UPDATED = "PATIENT_CONTACT_UPDATED";
    public static final String PATIENT_MEDICAL_DETAILS_UPDATED = "PATIENT_MEDICAL_DETAILS_UPDATED";
    public static final String PATIENT_STATUS_CHANGED = "PATIENT_STATUS_CHANGED";

    // Devices (Stage 6)
    public static final String DEVICE_REGISTERED = "DEVICE_REGISTERED";
    public static final String DEVICE_UPDATED = "DEVICE_UPDATED";
    public static final String DEVICE_ENROLLMENT_CHANGED = "DEVICE_ENROLLMENT_CHANGED";
    public static final String DEVICE_DEACTIVATED = "DEVICE_DEACTIVATED";
    public static final String DEVICE_REACTIVATED = "DEVICE_REACTIVATED";

    // Device assignments (Stage 7) - entity_type ASSIGNMENT
    public static final String DEVICE_ASSIGNED = "DEVICE_ASSIGNED";
    public static final String DEVICE_RELEASED = "DEVICE_RELEASED";

    // Monitoring sessions (Stage 8) - entity_type SESSION
    public static final String SESSION_STARTED = "SESSION_STARTED";
    public static final String SESSION_ENDED = "SESSION_ENDED";
    public static final String SESSION_CANCELLED = "SESSION_CANCELLED";

    private AuditAction() {
        // constants only - no instances
    }
}
