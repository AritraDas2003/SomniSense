package com.somnisense.backend.simulation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Configuration for the internal Python simulator worker and lease fencing. */
@Component
@ConfigurationProperties(prefix = "somnisense.simulation")
public class SimulationProperties {

    private String workerBaseUrl = "http://127.0.0.1:8095";
    private String internalKey = "";
    private String ingestionBaseUrl = "http://127.0.0.1:8080";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(5);
    private Duration leaseDuration = Duration.ofSeconds(30);

    public String getWorkerBaseUrl() {
        return workerBaseUrl;
    }

    public void setWorkerBaseUrl(String workerBaseUrl) {
        if (workerBaseUrl != null && !workerBaseUrl.isBlank()) {
            this.workerBaseUrl = workerBaseUrl.replaceAll("/+$", "");
        }
    }

    public String getInternalKey() {
        return internalKey;
    }

    public void setInternalKey(String internalKey) {
        this.internalKey = internalKey == null ? "" : internalKey.trim();
    }

    public String getIngestionBaseUrl() {
        return ingestionBaseUrl;
    }

    public void setIngestionBaseUrl(String ingestionBaseUrl) {
        if (ingestionBaseUrl != null && !ingestionBaseUrl.isBlank()) {
            this.ingestionBaseUrl = ingestionBaseUrl.replaceAll("/+$", "");
        }
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        if (connectTimeout != null && !connectTimeout.isNegative() && !connectTimeout.isZero()) {
            this.connectTimeout = connectTimeout;
        }
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        if (readTimeout != null && !readTimeout.isNegative() && !readTimeout.isZero()) {
            this.readTimeout = readTimeout;
        }
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        if (leaseDuration != null && !leaseDuration.isNegative() && !leaseDuration.isZero()) {
            this.leaseDuration = leaseDuration;
        }
    }
}
