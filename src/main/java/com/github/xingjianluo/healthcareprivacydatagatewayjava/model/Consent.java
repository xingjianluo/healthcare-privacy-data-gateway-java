package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

import java.time.Instant;
import java.util.Set;

/**
 * 患者授权：患者 -> 访问方，限定用途范围与有效期，可被撤回。
 */
public record Consent(
        String consentId,
        String patientId,
        String consumerId,
        Set<AccessPurpose> allowedPurposes,
        ConsentStatus status,
        Instant validFrom,
        Instant validUntil
) {
}
