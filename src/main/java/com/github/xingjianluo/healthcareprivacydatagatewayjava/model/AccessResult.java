package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

/** 网关最终返回：判定结论、可区分拒绝原因、审计关联ID与（允许时的）去标识化数据。 */
public record AccessResult(
        boolean allowed,
        DenialReason denialReason,
        String message,
        String requestId,
        DeidentifiedPatient data
) {
}
