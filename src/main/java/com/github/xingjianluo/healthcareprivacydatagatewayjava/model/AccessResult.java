package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

/**
 * 访问判定结果。成功时 record 非空；失败时 denyReason 非空。
 * auditEntryId 对应该请求在审计链上的记录，成功失败都有。
 */
public record AccessResult(
        AccessVerdict verdict,
        DenyReason denyReason,
        String message,
        DeidentifiedRecord record,
        String auditEntryId
) {
    public static AccessResult allow(DeidentifiedRecord record, String auditEntryId) {
        return new AccessResult(AccessVerdict.ALLOW, null, "访问允许，已返回去标识化最小字段记录",
                record, auditEntryId);
    }

    public static AccessResult deny(DenyReason reason, String message, String auditEntryId) {
        return new AccessResult(AccessVerdict.DENY, reason, message, null, auditEntryId);
    }
}
