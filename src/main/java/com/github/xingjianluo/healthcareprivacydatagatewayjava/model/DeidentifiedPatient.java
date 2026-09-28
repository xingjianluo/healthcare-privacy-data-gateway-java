package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

import java.util.List;
import java.util.Map;

/**
 * 去标识化后的最小返回字段：
 * 只含按访问方派生的假名、泛化人口学信息、科研所需结构化字段与脱敏文本。
 */
public record DeidentifiedPatient(
        String pseudonym,
        String gender,
        String ageBand,
        List<String> diagnosisCodes,
        Map<String, Double> labResults,
        String scrubbedNotes
) {
}
