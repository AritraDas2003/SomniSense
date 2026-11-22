package com.somnisense.backend.session.service;

import com.somnisense.backend.common.util.RandomCodes;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Generates session codes like "SES-20261121-7K3Q9M" (decision 2026-11-21):
 * the UTC start date (so staff can see which night it belongs to) + 6 random characters (unguessable).
 */
@Component
public class SessionCodeGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    public String generate(Instant startedAt) {
        return RandomCodes.generate("SES-" + DATE.format(startedAt) + "-", 6);
    }
}
