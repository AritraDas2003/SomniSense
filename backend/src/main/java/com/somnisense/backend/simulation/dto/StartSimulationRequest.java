package com.somnisense.backend.simulation.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Request payload for starting a simulated monitoring stream on an active SIMULATED session. */
public class StartSimulationRequest {

    @NotBlank
    @Size(max = 60)
    private String scenarioId = "normal_sleep";

    private Long seed;

    @DecimalMin("0.1")
    @DecimalMax("10.0")
    private BigDecimal cadenceHz = new BigDecimal("1.00");

    @DecimalMin("0.5")
    @DecimalMax("60.0")
    private BigDecimal playbackSpeed = new BigDecimal("1.00");

    @Size(max = 60)
    private String qualityPreset;

    public String getScenarioId() {
        return scenarioId;
    }

    public void setScenarioId(String scenarioId) {
        this.scenarioId = scenarioId;
    }

    public Long getSeed() {
        return seed;
    }

    public void setSeed(Long seed) {
        this.seed = seed;
    }

    public BigDecimal getCadenceHz() {
        return cadenceHz;
    }

    public void setCadenceHz(BigDecimal cadenceHz) {
        this.cadenceHz = cadenceHz;
    }

    public BigDecimal getPlaybackSpeed() {
        return playbackSpeed;
    }

    public void setPlaybackSpeed(BigDecimal playbackSpeed) {
        this.playbackSpeed = playbackSpeed;
    }

    public String getQualityPreset() {
        return qualityPreset;
    }

    public void setQualityPreset(String qualityPreset) {
        this.qualityPreset = qualityPreset;
    }
}
