package com.somnisense.backend.simulation.entity;

/** Lifecycle state of a simulator run (matches V5 chk_simulation_runs_status_valid). */
public enum SimulationStatus {
    PENDING,
    RUNNING,
    PAUSED,
    COMPLETED,
    STOPPED,
    FAILED
}
