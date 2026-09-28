package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

import java.time.Instant;

/**
 * 审计链上的一条记录。无论判定成功或失败都会留痕。
 *
 * @param sequence       单调递增序号（从 0 开始）
 * @param entryId        本条记录唯一ID（UUID）
 * @param timestamp      发生时间
 * @param accessorId     访问方ID（失败请求也可能有）
 * @param patientId      请求的患者ID
 * @param purpose        请求用途
 * @param verdict        ALLOW / DENY
 * @param denyReason     拒绝原因（允许时为 null）
 * @param prevHash       上一条记录的哈希（创世记录为固定常量）
 * @param currentHash    本条记录的哈希
 */
public record AuditEntry(
        long sequence,
        String entryId,
        Instant timestamp,
        String accessorId,
        String patientId,
        String purpose,
        String verdict,
        String denyReason,
        String prevHash,
        String currentHash
) {
}
