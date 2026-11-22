package com.somnisense.backend.device.dto;

import com.somnisense.backend.common.domain.SourceType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/v1/devices:
 * {"deviceCode":"OXI-0042" (optional), "deviceType":"PHYSICAL", "modelName":"...", "serialNumber":"..."}
 *
 * deviceCode: optional. If given: 3-50 chars of A-Z a-z 0-9 - _ (stored upper-case). If blank, the backend
 * generates one like DEV-7K3Q9M2X. There are no secret/credential fields: device secrets are never handled here.
 */
public class RegisterDeviceRequest {

    /** Spaces around the code (e.g. copied from a label) are allowed and trimmed; spaces inside are not. */
    public static final String DEVICE_CODE_REGEX = "^\\s*[A-Za-z0-9_-]{3,50}\\s*$";

    @Pattern(regexp = DEVICE_CODE_REGEX, message = "must be 3-50 characters: letters, digits, '-' or '_'")
    private String deviceCode;

    @NotNull
    private SourceType deviceType;

    @Size(max = 100)
    private String modelName;

    @Size(max = 100)
    private String serialNumber;

    public String getDeviceCode() {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }

    public SourceType getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(SourceType deviceType) {
        this.deviceType = deviceType;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }
}
