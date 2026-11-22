package com.somnisense.backend.device.dto;

import jakarta.validation.constraints.Size;

/**
 * Body of PUT /api/v1/devices/{id}: replaces modelName and serialNumber (blank/missing = cleared).
 * deviceCode and deviceType can never change.
 */
public class UpdateDeviceRequest {

    @Size(max = 100)
    private String modelName;

    @Size(max = 100)
    private String serialNumber;

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
