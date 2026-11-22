package com.somnisense.backend.device.service;

import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.audit.service.AuditService;
import com.somnisense.backend.patient.repository.PatientRepository;
import com.somnisense.backend.common.exception.ApiException;
import com.somnisense.backend.device.entity.ConnectivityStatus;
import com.somnisense.backend.device.repository.DeviceRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests (no Spring/database): freshness boundaries and device-code collision retry. */
class DeviceUnitTest {

    private final ConnectivityPolicy policy = new ConnectivityPolicy(Duration.ofSeconds(30), Duration.ofSeconds(120));
    private final Instant now = Instant.parse("2026-11-21T12:00:00Z");

    @Test
    void connectivityBoundaries() {
        assertThat(policy.evaluate(null, now)).isEqualTo(ConnectivityStatus.UNKNOWN);
        assertThat(policy.evaluate(now, now)).isEqualTo(ConnectivityStatus.CONNECTED);
        assertThat(policy.evaluate(now.minusSeconds(30), now)).isEqualTo(ConnectivityStatus.CONNECTED);   // boundary included
        assertThat(policy.evaluate(now.minusSeconds(31), now)).isEqualTo(ConnectivityStatus.STALE);
        assertThat(policy.evaluate(now.minusSeconds(120), now)).isEqualTo(ConnectivityStatus.STALE);
        assertThat(policy.evaluate(now.minusSeconds(121), now)).isEqualTo(ConnectivityStatus.DISCONNECTED);
        // a device clock slightly ahead of ours must not break anything
        assertThat(policy.evaluate(now.plusSeconds(2), now)).isEqualTo(ConnectivityStatus.CONNECTED);
    }

    @Test
    void misconfiguredThresholdsFailAtStartup() {
        assertThatThrownBy(() -> new ConnectivityPolicy(Duration.ofSeconds(60), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void generatedCodeClashIsRetriedThenGivesUp() {
        DeviceRepository repo = mock(DeviceRepository.class);
        DeviceCodeGenerator generator = mock(DeviceCodeGenerator.class);
        DeviceService service = new DeviceService(repo, generator, policy, mock(AuditService.class),
                mock(DeviceAssignmentRepository.class), mock(PatientRepository.class));

        when(generator.generate()).thenReturn("DEV-TAKEN222", "DEV-FREE3333");
        when(repo.existsByDeviceCode("DEV-TAKEN222")).thenReturn(true);
        assertThat(service.newUniqueCode()).isEqualTo("DEV-FREE3333");

        when(generator.generate()).thenReturn("DEV-TAKEN222");
        when(repo.existsByDeviceCode(anyString())).thenReturn(true);
        assertThatThrownBy(service::newUniqueCode).isInstanceOf(ApiException.class);
    }
}
