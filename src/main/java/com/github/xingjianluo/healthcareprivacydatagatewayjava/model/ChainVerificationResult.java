package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

import java.util.List;

/** 审计链校验结果。 */
public record ChainVerificationResult(
        boolean valid,
        long expectedCount,
        long actualCount,
        List<String> brokenAtSequences,
        String detail
) {
}
