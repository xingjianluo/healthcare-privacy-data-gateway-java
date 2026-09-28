package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

import java.util.List;
import java.util.Map;

/**
 * 患者原始记录（本地数据模拟）。
 * 含直接标识符、结构化科研字段与自由文本病历片段。
 */
public record PatientRecord(
        String patientId,
        String fullName,
        String idCardNo,
        String phone,
        String gender,
        int birthYear,
        List<String> diagnosisCodes,
        Map<String, Double> labResults,
        String notes
) {
}
