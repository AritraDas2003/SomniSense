package com.somnisense.backend.patient.service;

import com.somnisense.backend.assignment.repository.DeviceAssignmentRepository;
import com.somnisense.backend.audit.service.AuditService;
import com.somnisense.backend.device.repository.DeviceRepository;
import com.somnisense.backend.device.service.ConnectivityPolicy;

import java.time.Duration;
import com.somnisense.backend.common.exception.ApiException;
import com.somnisense.backend.common.exception.ErrorCode;
import com.somnisense.backend.patient.repository.PatientContactRepository;
import com.somnisense.backend.patient.repository.PatientMedicalDetailsRepository;
import com.somnisense.backend.patient.repository.PatientRepository;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests (no Spring/database) for patient code generation and the collision retry. */
class PatientCodeTest {

    @Test
    void generatedCodesHaveTheAgreedFormatAndDoNotRepeat() {
        PatientCodeGenerator generator = new PatientCodeGenerator();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String code = generator.generate();
            assertThat(code).matches("^PT-[2-9A-HJ-NP-Z]{8}$");   // no 0, 1, I, O
            seen.add(code);
        }
        assertThat(seen).hasSize(2000);
    }

    @Test
    void anExistingCodeIsSkippedAndANewOneTried() {
        PatientRepository repo = mock(PatientRepository.class);
        PatientCodeGenerator generator = mock(PatientCodeGenerator.class);
        when(generator.generate()).thenReturn("PT-TAKEN222", "PT-FREE3333");
        when(repo.existsByPatientCode("PT-TAKEN222")).thenReturn(true);
        when(repo.existsByPatientCode("PT-FREE3333")).thenReturn(false);

        assertThat(service(repo, generator).newUniqueCode()).isEqualTo("PT-FREE3333");
    }

    @Test
    void givesUpAfterFiveClashesInsteadOfLoopingForever() {
        PatientRepository repo = mock(PatientRepository.class);
        PatientCodeGenerator generator = mock(PatientCodeGenerator.class);
        when(generator.generate()).thenReturn("PT-TAKEN222");
        when(repo.existsByPatientCode(anyString())).thenReturn(true);

        assertThatThrownBy(() -> service(repo, generator).newUniqueCode())
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        verify(generator, times(PatientService.MAX_CODE_ATTEMPTS)).generate();
    }

    private static PatientService service(PatientRepository repo, PatientCodeGenerator generator) {
        return new PatientService(repo, mock(PatientContactRepository.class), mock(PatientMedicalDetailsRepository.class),
                generator, mock(AuditService.class), mock(DeviceAssignmentRepository.class), mock(DeviceRepository.class),
                new ConnectivityPolicy(Duration.ofSeconds(30), Duration.ofSeconds(120)),
                mock(com.somnisense.backend.session.repository.MonitoringSessionRepository.class),
                mock(com.somnisense.backend.reading.repository.SensorReadingRepository.class));
    }
}
