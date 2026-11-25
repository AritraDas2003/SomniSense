package com.somnisense.backend.ai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for the internal Python FastAPI Model C sidecar.
 */
@Component
public class AiProperties {

    private final String baseUrl;
    private final String apiKey;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final String modelVersion;

    public AiProperties(
            @Value("${somnisense.ai.base-url:http://127.0.0.1:8090}") String baseUrl,
            @Value("${somnisense.ai.api-key:}") String apiKey,
            @Value("${somnisense.ai.connect-timeout:2s}") Duration connectTimeout,
            @Value("${somnisense.ai.read-timeout:5s}") Duration readTimeout,
            @Value("${somnisense.ai.model-version:phase7_model_c:1.0.0}") String modelVersion) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.modelVersion = modelVersion;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public String getModelVersion() {
        return modelVersion;
    }
}
