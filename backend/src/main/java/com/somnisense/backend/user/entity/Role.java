package com.somnisense.backend.user.entity;

/**
 * Staff roles allowed by the approved schema (users.role comment: 'ADMIN or OPERATOR').
 * The database has no CHECK constraint, so this enum is what enforces the allowed values.
 * Stored as text ("ADMIN"/"OPERATOR") via @Enumerated(EnumType.STRING) in User.
 */
public enum Role {
    ADMIN,
    OPERATOR
}
