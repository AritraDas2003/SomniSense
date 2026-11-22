package com.somnisense.backend.common.exception;

/**
 * 409 - the request is well-formed but clashes with the current state.
 *
 * Examples: the device already has an active assignment; the session is already ENDED;
 * the username is already taken.
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(ErrorCode.CONFLICT, message);
    }
}
