package com.somnisense.backend.device.service;

import com.somnisense.backend.device.entity.ConnectivityStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Turns "when did we last receive data?" into CONNECTED / STALE / DISCONNECTED / UNKNOWN (decision 2026-11-21).
 *
 * Computed at READ time from last_seen_at, so the status is always current - a stored "CONNECTED" would stay
 * wrong forever if the device simply stopped sending. Defaults for a 1 Hz stream:
 *   age <= 30 s  -> CONNECTED
 *   age <= 120 s -> STALE
 *   older        -> DISCONNECTED
 *   never seen   -> UNKNOWN
 * Configurable with somnisense.devices.stale-after / disconnected-after (e.g. "45s").
 */
@Component
public class ConnectivityPolicy {

    private final Duration staleAfter;
    private final Duration disconnectedAfter;

    public ConnectivityPolicy(@Value("${somnisense.devices.stale-after:30s}") Duration staleAfter,
                              @Value("${somnisense.devices.disconnected-after:120s}") Duration disconnectedAfter) {
        if (disconnectedAfter.compareTo(staleAfter) <= 0) {
            throw new IllegalArgumentException("disconnected-after must be longer than stale-after");
        }
        this.staleAfter = staleAfter;
        this.disconnectedAfter = disconnectedAfter;
    }

    public ConnectivityStatus evaluate(Instant lastSeenAt, Instant now) {
        if (lastSeenAt == null) {
            return ConnectivityStatus.UNKNOWN;
        }
        Duration age = Duration.between(lastSeenAt, now);
        if (age.compareTo(staleAfter) <= 0) {
            return ConnectivityStatus.CONNECTED;
        }
        if (age.compareTo(disconnectedAfter) <= 0) {
            return ConnectivityStatus.STALE;
        }
        return ConnectivityStatus.DISCONNECTED;
    }

    public Duration getStaleAfter() {
        return staleAfter;
    }

    public Duration getDisconnectedAfter() {
        return disconnectedAfter;
    }
}
