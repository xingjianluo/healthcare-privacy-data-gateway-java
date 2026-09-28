package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

import java.util.List;
import java.util.Map;

/**
 * 去标识化后的返回记录：只含稳定假名与科研需要的最小结构化字段，
 * 不含任何直接标识符。
 */
public record DeidentifiedRecord(
        String pseudonym,
        String gender,
        List<String> diagnoses,
        Map<String, String> labResults,
        String clinicalNoteMasked
) {
}
