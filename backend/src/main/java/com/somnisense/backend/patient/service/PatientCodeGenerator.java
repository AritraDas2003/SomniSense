package com.somnisense.backend.patient.service;

import com.somnisense.backend.common.util.RandomCodes;
import org.springframework.stereotype.Component;

/**
 * Generates patient codes like "PT-7K3Q9M2X" (decision 2026-11-21).
 *
 * - 8 characters from a 32-character alphabet without look-alikes (no 0/O, 1/I) = 32^8 ~ 1.1 trillion codes.
 * - Random, so the code reveals nothing about how many patients exist or when they were registered.
 * - Needs no database sequence (no schema change). The very rare clash with an existing code is handled by
 *   PatientService, which simply tries another code.
 * A separate bean (rather than a static call) so tests can replace it with a fake that forces clashes.
 */
@Component
public class PatientCodeGenerator {

    public static final String PREFIX = "PT-";

    public String generate() {
        return RandomCodes.generate(PREFIX, 8);
    }
}
