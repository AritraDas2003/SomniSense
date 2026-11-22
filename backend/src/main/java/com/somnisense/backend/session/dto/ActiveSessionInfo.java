package com.somnisense.backend.session.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.UUID;

/** "Is this patient being monitored right now?" - on patient list rows and detail. Null when not. */
@JsonPropertyOrder({"sessionId", "sessionCode", "startedAt"})
public class ActiveSessionInfo {

    private final UUID sessionId;
    private final String sessionCode;
    private final Instant startedAt;

    public ActiveSessionInfo(UUID sessionId, String sessionCode, Instant startedAt) {
        this.sessionId = sessionId;
        this.sessionCode = sessionCode;
        this.startedAt = startedAt;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getSessionCode() {
        return sessionCode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }
}
