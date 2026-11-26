package com.somnisense.backend.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** Externalized configuration for the Stage 4 sliding-window scheduler and bounded retry engine. */
@Component
@ConfigurationProperties(prefix = "somnisense.ai.scheduler")
public class AiSchedulerProperties {

    private boolean enabled = true;
    private long fixedDelayMs = 2000L;
    private Duration graceLag = Duration.ofSeconds(3);
    private Duration windowDuration = Duration.ofSeconds(60);
    private Duration windowStride = Duration.ofSeconds(10);
    private Duration causalContextDuration = Duration.ofSeconds(360);
    private int maxWindowsPerSession = 6;
    private int workerThreads = 4;
    private int maxRetryAttempts = 4;
    private List<Long> retryBackoffSeconds = List.of(10L, 30L, 90L);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getFixedDelayMs() {
        return fixedDelayMs;
    }

    public void setFixedDelayMs(long fixedDelayMs) {
        if (fixedDelayMs > 0) {
            this.fixedDelayMs = fixedDelayMs;
        }
    }

    public Duration getGraceLag() {
        return graceLag;
    }

    public void setGraceLag(Duration graceLag) {
        if (graceLag != null && !graceLag.isNegative()) {
            this.graceLag = graceLag;
        }
    }

    public Duration getWindowDuration() {
        return windowDuration;
    }

    public void setWindowDuration(Duration windowDuration) {
        if (windowDuration != null && !windowDuration.isNegative() && !windowDuration.isZero()) {
            this.windowDuration = windowDuration;
        }
    }

    public Duration getWindowStride() {
        return windowStride;
    }

    public void setWindowStride(Duration windowStride) {
        if (windowStride != null && !windowStride.isNegative() && !windowStride.isZero()) {
            this.windowStride = windowStride;
        }
    }

    public Duration getCausalContextDuration() {
        return causalContextDuration;
    }

    public void setCausalContextDuration(Duration causalContextDuration) {
        if (causalContextDuration != null && !causalContextDuration.isNegative() && !causalContextDuration.isZero()) {
            this.causalContextDuration = causalContextDuration;
        }
    }

    public int getMaxWindowsPerSession() {
        return maxWindowsPerSession;
    }

    public void setMaxWindowsPerSession(int maxWindowsPerSession) {
        if (maxWindowsPerSession > 0) {
            this.maxWindowsPerSession = maxWindowsPerSession;
        }
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        if (workerThreads > 0) {
            this.workerThreads = workerThreads;
        }
    }

    public int getMaxRetryAttempts() {
        return maxRetryAttempts;
    }

    public void setMaxRetryAttempts(int maxRetryAttempts) {
        if (maxRetryAttempts > 0) {
            this.maxRetryAttempts = maxRetryAttempts;
        }
    }

    public List<Long> getRetryBackoffSeconds() {
        return retryBackoffSeconds;
    }

    public void setRetryBackoffSeconds(List<Long> retryBackoffSeconds) {
        if (retryBackoffSeconds != null && !retryBackoffSeconds.isEmpty()) {
            this.retryBackoffSeconds = List.copyOf(retryBackoffSeconds);
        }
    }
}
