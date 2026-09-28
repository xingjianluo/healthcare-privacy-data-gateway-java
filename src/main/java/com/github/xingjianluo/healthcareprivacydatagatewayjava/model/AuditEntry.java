package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

/**
 * 审计链上的一条记录。entryHash = SHA-256(规范内容 + previousHash)，
 * 任何字段被改动都会破坏后续整条链的校验。
 */
public record AuditEntry(
        long sequence,
        String timestamp,
        String requestId,
        String patientId,
        String consumerId,
        String purpose,
        String decision,
        String denialReason,
        String previousHash,
        String entryHash
) {
}
