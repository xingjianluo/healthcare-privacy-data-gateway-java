package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

/** 审计链校验结果：是否完整、首个断裂位置与说明。 */
public record AuditVerificationResult(
        boolean valid,
        Long brokenAtSequence,
        String message
) {
}
