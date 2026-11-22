package com.somnisense.backend.security.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The shared secret that the simulator / trusted gateway sends in the X-Ingestion-Key header (decision F8).
 *
 * - Read from somnisense.ingestion.api-key (env INGESTION_API_KEY). Never logged, never returned.
 * - Must be at least 32 characters; otherwise ingestion is DISABLED (every request gets 401) and a warning is logged.
 * - matches() compares SHA-256 digests with MessageDigest.isEqual, which takes the same time whether the first or
 *   the last character differs, so response timing cannot be used to guess the key ("timing attack").
 *
 * Note: this proves the sender knows the shared key - it does not prove which physical device sent the data.
 */
@Component
public class IngestionApiKey {

    public static final String HEADER = "X-Ingestion-Key";
    static final int MIN_LENGTH = 32;
    private static final Logger log = LoggerFactory.getLogger(IngestionApiKey.class);

    private final byte[] expectedDigest;

    public IngestionApiKey(@Value("${somnisense.ingestion.api-key:}") String configuredKey) {
        if (configuredKey == null || configuredKey.length() < MIN_LENGTH) {
            log.warn("Reading ingestion is DISABLED: somnisense.ingestion.api-key (INGESTION_API_KEY) must be set "
                    + "to at least {} characters.", MIN_LENGTH);
            this.expectedDigest = null;
        } else {
            this.expectedDigest = sha256(configuredKey);
        }
    }

    public boolean isEnabled() {
        return expectedDigest != null;
    }

    public boolean matches(String presented) {
        if (expectedDigest == null || presented == null || presented.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(expectedDigest, sha256(presented));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
