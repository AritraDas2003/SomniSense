package com.somnisense.backend.common.util;

/** Small text helpers shared by entities. */
public final class Strings {

    private Strings() {
    }

    /** Trims the text; an empty or blank value becomes null, so the database stores "no value" consistently. */
    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
