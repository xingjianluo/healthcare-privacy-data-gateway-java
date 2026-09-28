package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

/** 授权判定中间结果：允许/拒绝 + 可区分原因 + 命中的授权。 */
public record AccessDecision(
        boolean allowed,
        DenialReason denialReason,
        Consent consent
) {
}
