package com.somnisense.backend.common.util;

import java.security.SecureRandom;

/**
 * Random human-friendly codes such as "PT-7K3Q9M2X" or "DEV-L6NP4WLM".
 * The alphabet leaves out look-alike characters (0/O, 1/I), so codes can be read aloud or copied from a label.
 */
public final class RandomCodes {

    public static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private RandomCodes() {
    }

    public static String generate(String prefix, int length) {
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
