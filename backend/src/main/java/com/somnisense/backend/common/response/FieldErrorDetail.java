package com.somnisense.backend.common.response;

/**
 * One invalid field inside a VALIDATION_FAILED error, e.g. {"field":"fullName","message":"must not be blank"}.
 *
 * The rejected VALUE is deliberately not included: it could be a password or patient data.
 */
public class FieldErrorDetail {

    private final String field;
    private final String message;

    public FieldErrorDetail(String field, String message) {
        this.field = field;
        this.message = message;
    }

    // Jackson turns each public getter into a JSON property: getField() -> "field".
    public String getField() {
        return field;
    }

    public String getMessage() {
        return message;
    }
}
