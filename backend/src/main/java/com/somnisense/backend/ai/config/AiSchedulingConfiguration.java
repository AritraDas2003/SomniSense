package com.somnisense.backend.ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring scheduling only when somnisense.ai.scheduler.enabled=true so test suites can
 * drive SlidingWindowScheduler deterministically without background tick races.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "somnisense.ai.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AiSchedulingConfiguration {
}
