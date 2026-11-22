package com.somnisense.backend.patient.repository;

import com.somnisense.backend.common.domain.SourceType;
import com.somnisense.backend.patient.entity.Patient;
import com.somnisense.backend.patient.entity.PatientStatus;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/** Optional filters for the patient list (each one adds nothing when its value is null/blank). */
public final class PatientSpecifications {

    private PatientSpecifications() {
    }

    public static Specification<Patient> hasSourceType(SourceType sourceType) {
        return (root, query, cb) -> sourceType == null ? null : cb.equal(root.get("sourceType"), sourceType);
    }

    public static Specification<Patient> hasStatus(PatientStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    /** Case-insensitive "contains" on patient code, full name or alias (UI plan C17). */
    public static Specification<Patient> matchesText(String text) {
        return (root, query, cb) -> {
            if (text == null || text.isBlank()) {
                return null;
            }
            String escaped = text.trim().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            String pattern = "%" + escaped + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("patientCode")), pattern, '\\'),
                    cb.like(cb.lower(root.get("fullName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("alias")), pattern, '\\'));
        };
    }
}
