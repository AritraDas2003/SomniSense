package com.somnisense.backend.common.exception;

/**
 * 404 - the requested record does not exist (or the caller may not know it exists).
 *
 * Example: {@code throw new ResourceNotFoundException("Patient", patientId);}
 * produces the message "Patient not found: 8f1c...".
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String resourceName, Object id) {
        super(ErrorCode.RESOURCE_NOT_FOUND, resourceName + " not found: " + id);
    }
}
