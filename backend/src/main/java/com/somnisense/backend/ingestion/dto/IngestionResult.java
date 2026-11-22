package com.somnisense.backend.ingestion.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response of POST /api/v1/ingestion/readings (always 200 once the batch reached a valid session):
 * accepted   = newly stored readings,
 * duplicates = readingKeys already stored for this device (or repeated within the batch) - safely skipped,
 * rejected   = readings that broke a rule, each with its position, key and reason. Nothing was changed for them.
 * The sender may resend the whole batch at any time: accepted ones then come back as duplicates.
 */
@JsonPropertyOrder({"deviceCode", "sessionId", "sessionCode", "receivedAt", "accepted", "duplicates", "rejected"})
public class IngestionResult {

    /** One rejected reading: index = position in the request's readings array (0-based). */
    @JsonPropertyOrder({"index", "readingKey", "reason", "message"})
    public static class Rejected {
        private final int index;
        private final String readingKey;
        private final String reason;
        private final String message;

        public Rejected(int index, String readingKey, RejectReason reason, String message) {
            this.index = index;
            this.readingKey = readingKey;
            this.reason = reason.name();
            this.message = message;
        }

        public int getIndex() {
            return index;
        }

        public String getReadingKey() {
            return readingKey;
        }

        public String getReason() {
            return reason;
        }

        public String getMessage() {
            return message;
        }
    }

    /** Machine-readable rejection reasons. */
    public enum RejectReason {
        READING_MISSING,
        INVALID_READING_KEY,
        MISSING_MEASURED_AT,
        MEASURED_AT_IN_FUTURE,
        MEASURED_AT_BEFORE_SESSION,
        SPO2_OUT_OF_RANGE,
        HEART_RATE_OUT_OF_RANGE,
        SIGNAL_QUALITY_NOT_SUPPORTED
    }

    private final String deviceCode;
    private final UUID sessionId;
    private final String sessionCode;
    private final Instant receivedAt;
    private final int accepted;
    private final int duplicates;
    private final List<Rejected> rejected;

    public IngestionResult(String deviceCode, UUID sessionId, String sessionCode, Instant receivedAt, int accepted,
                           int duplicates, List<Rejected> rejected) {
        this.deviceCode = deviceCode;
        this.sessionId = sessionId;
        this.sessionCode = sessionCode;
        this.receivedAt = receivedAt;
        this.accepted = accepted;
        this.duplicates = duplicates;
        this.rejected = List.copyOf(rejected);
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getSessionCode() {
        return sessionCode;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public int getAccepted() {
        return accepted;
    }

    public int getDuplicates() {
        return duplicates;
    }

    public List<Rejected> getRejected() {
        return rejected;
    }
}
