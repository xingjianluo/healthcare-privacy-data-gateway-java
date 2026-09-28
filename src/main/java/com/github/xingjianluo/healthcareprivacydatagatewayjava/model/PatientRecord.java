package com.github.xingjianluo.healthcareprivacydatagatewayjava.model;

import java.util.List;
import java.util.Map;

/**
 * 本地模拟的患者原始记录。
 *
 * @param patientId    真实患者ID（直接标识符，绝不能出现在去标识化结果中）
 * @param name         姓名（直接标识符）
 * @param idCard       身份证号（直接标识符）
 * @param phone        手机号（直接标识符）
 * @param birthDate    出生日期（直接标识符）
 * @param gender       性别（科研结构化字段，保留）
 * @param diagnoses    诊断编码（科研结构化字段，保留）
 * @param labResults   检验结果（科研结构化字段，保留）
 * @param clinicalNote 自由文本临床记录（可能包含敏感片段，需脱敏）
 */
public record PatientRecord(
        String patientId,
        String name,
        String idCard,
        String phone,
        String birthDate,
        String gender,
        List<String> diagnoses,
        Map<String, String> labResults,
        String clinicalNote
) {
}
