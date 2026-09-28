package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

import java.time.Instant;
import java.util.Set;

/**
 * 患者对某访问方的授权。
 *
 * @param grantId       授权ID
 * @param patientId     患者ID
 * @param accessorId    被授权的访问方ID
 * @param allowedPurposes 允许的用途集合
 * @param status        状态（ACTIVE / REVOKED）
 * @param grantedAt     授权时间
 * @param expiresAt     过期时间
 * @param revokedAt     撤回时间（未撤回为 null）
 */
public record Grant(
        String grantId,
        String patientId,
        String accessorId,
        Set<Purpose> allowedPurposes,
        GrantStatus status,
        Instant grantedAt,
        Instant expiresAt,
        Instant revokedAt
) {
}
