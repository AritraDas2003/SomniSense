package com.somnisense.backend.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.somnisense.backend.device.entity.ConnectivityStatus;
import com.somnisense.backend.patient.dto.PatientSummaryResponse;
import com.somnisense.backend.prediction.dto.PredictionResponse;
import com.somnisense.backend.reading.dto.LatestReadingInfo;
import com.somnisense.backend.simulation.entity.SimulationRun;
import com.somnisense.backend.simulation.entity.SimulationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Consolidated read-only dashboard snapshot returned by GET /api/v1/dashboard/summary.
 */
@JsonPropertyOrder({"generatedAt", "modelVersion", "summary", "activeSessionsList", "recentPatients"})
public record DashboardSummaryResponse(
        Instant generatedAt,
        String modelVersion,
        SummaryMetrics summary,
        List<ActiveSessionItem> activeSessionsList,
        List<PatientSummaryResponse> recentPatients
) {

    @JsonPropertyOrder({
            "patientCount", "activeSessions", "activeSessionsReturned", "activeSessionsTruncated",
            "itemsNeedingReview", "dataIssues", "activeSessionStatuses"
    })
    public record SummaryMetrics(
            long patientCount,
            long activeSessions,
            int activeSessionsReturned,
            boolean activeSessionsTruncated,
            long itemsNeedingReview,
            long dataIssues,
            ActiveSessionStatusCounts activeSessionStatuses
    ) {
    }

    @JsonPropertyOrder({"completed", "needsReview", "rejected", "error", "degradedWindows", "pendingRetryJobs"})
    public record ActiveSessionStatusCounts(
            long completed,
            long needsReview,
            long rejected,
            long error,
            long degradedWindows,
            long pendingRetryJobs
    ) {
    }

    @JsonPropertyOrder({
            "sessionId", "sessionCode", "patientId", "patientCode", "patientDisplayName",
            "sourceType", "deviceId", "deviceCode", "connectivityStatus", "deviceLastSeenAt",
            "startedAt", "simulation", "latestReading", "latestPrediction"
    })
    public record ActiveSessionItem(
            UUID sessionId,
            String sessionCode,
            UUID patientId,
            String patientCode,
            String patientDisplayName,
            String sourceType,
            UUID deviceId,
            String deviceCode,
            ConnectivityStatus connectivityStatus,
            Instant deviceLastSeenAt,
            Instant startedAt,
            SimulationSummary simulation,
            LatestReadingInfo latestReading,
            PredictionResponse latestPrediction
    ) {
    }

    @JsonPropertyOrder({"id", "scenarioId", "status", "lastStep", "workerGeneration"})
    public record SimulationSummary(
            UUID id,
            String scenarioId,
            SimulationStatus status,
            long lastStep,
            long workerGeneration
    ) {
        public static SimulationSummary from(SimulationRun run) {
            return run == null ? null : new SimulationSummary(
                    run.getId(),
                    run.getScenarioId(),
                    run.getStatus(),
                    run.getLastStep(),
                    run.getWorkerGeneration()
            );
        }
    }
}
