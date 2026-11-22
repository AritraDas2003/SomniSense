package com.somnisense.backend.common.exception;

/**
 * 400 - a business rule rejected the request, in a way that @Valid annotations cannot express.
 *
 * Example: "Assignment does not belong to this patient", or "Cannot deactivate the last active Admin".
 * (Simple field rules like @NotBlank are handled automatically as VALIDATION_FAILED instead.)
 */
public class InvalidRequestException extends ApiException {

    public InvalidRequestException(String message) {
        super(ErrorCode.INVALID_REQUEST, message);
    }
}
