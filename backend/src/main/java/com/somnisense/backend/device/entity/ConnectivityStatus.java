package com.somnisense.backend.device.entity;

/**
 * Is data arriving from the device? (doc 03 section 8 "Transport/device" states.)
 *
 * CONNECTED - data arrived recently.  STALE - data is late.  DISCONNECTED - no data for a long time.
 * UNKNOWN - the device has never sent data.
 *
 * IMPORTANT: "CONNECTED" only means data is arriving. It never means the patient is medically fine.
 * Staff never set this; it is derived from last_seen_at (see ConnectivityPolicy).
 */
public enum ConnectivityStatus {
    CONNECTED,
    STALE,
    DISCONNECTED,
    UNKNOWN
}
