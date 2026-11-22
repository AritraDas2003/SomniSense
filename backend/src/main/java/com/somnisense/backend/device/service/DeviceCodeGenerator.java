package com.somnisense.backend.device.service;

import com.somnisense.backend.common.util.RandomCodes;
import org.springframework.stereotype.Component;

/** Generates device codes like "DEV-7K3Q9M2X" when staff leave the code blank (decision 2026-11-21). */
@Component
public class DeviceCodeGenerator {

    public static final String PREFIX = "DEV-";

    public String generate() {
        return RandomCodes.generate(PREFIX, 8);
    }
}
