package com.somnisense.backend.ingestion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Body of POST /api/v1/ingestion/readings (decision 2026-11-21):
 * {"deviceCode":"DEV-H2FP2ABH", "sessionCode":"SES-..." (optional), "readings":[ ... 1 to 600 items ... ]}
 *
 * The sender names the DEVICE; the backend finds that device's ACTIVE monitoring session itself.
 * There is no patientId field: a sender can never choose which patient data belongs to.
 */
public class IngestReadingsRequest {

    public static final int MAX_BATCH_SIZE = 600;   // 10 minutes at 1 Hz

    @NotBlank
    @Size(max = 50)
    private String deviceCode;

    @Size(max = 40)
    private String sessionCode;

    private UUID simulationRunId;

    private Long workerGeneration;

    @Size(max = 100)
    private String workerInstanceId;

    @NotNull
    @Size(min = 1, max = MAX_BATCH_SIZE)
    private List<IngestReadingItem> readings;

    public String getDeviceCode() {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }

    public String getSessionCode() {
        return sessionCode;
    }

    public void setSessionCode(String sessionCode) {
        this.sessionCode = sessionCode;
    }

    public List<IngestReadingItem> getReadings() {
        return readings;
    }

    public void setReadings(List<IngestReadingItem> readings) {
        this.readings = readings;
    }

    public UUID getSimulationRunId() {
        return simulationRunId;
    }

    public void setSimulationRunId(UUID simulationRunId) {
        this.simulationRunId = simulationRunId;
    }

    public Long getWorkerGeneration() {
        return workerGeneration;
    }

    public void setWorkerGeneration(Long workerGeneration) {
        this.workerGeneration = workerGeneration;
    }

    public String getWorkerInstanceId() {
        return workerInstanceId;
    }

    public void setWorkerInstanceId(String workerInstanceId) {
        this.workerInstanceId = workerInstanceId;
    }
}
