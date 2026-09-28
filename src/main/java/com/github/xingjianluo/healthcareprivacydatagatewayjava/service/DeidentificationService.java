package com.github.xingjianluo.healthcareprivacydatagatewayjava.service;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.DeidentifiedPatient;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.PatientRecord;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 去标识化服务，口径见 docs：
 * <ul>
 *   <li><b>假名</b>：pseudonym = HMAC-SHA256(secret, consumerId + ":" + patientId)
 *       的前 16 位十六进制。同一访问方对同一患者多次访问得到相同假名（稳定可复现）；
 *       假名与访问方绑定，不同访问方得到不同假名，无法跨方关联同一自然人。</li>
 *   <li><b>最小字段</b>：姓名、身份证号、手机号等直接标识符一律不返回；
 *       出生年份泛化为 10 岁年龄段；性别保留。</li>
 *   <li><b>结构化字段保留</b>：诊断编码与检验结果是科研所需字段，原样返回，不做脱敏。</li>
 *   <li><b>自由文本脱敏</b>：身份证号、手机号、患者姓名以及敏感疾病/行为关键词
 *       替换为 [脱敏] 占位符。</li>
 * </ul>
 */
@Service
public class DeidentificationService {

    static final String REDACTION = "[脱敏]";

    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[0-9Xx]");
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    /** 自由文本中的敏感片段关键词（仅作用于文本；结构化诊断编码不受影响）。 */
    private static final List<String> SENSITIVE_TERMS = List.of(
            "HIV", "hiv", "AIDS", "Aids", "艾滋病",
            "精神分裂", "抑郁症", "梅毒", "淋病",
            "吸毒", "药物滥用", "酒精依赖"
    );

    private final String pseudonymSecret;
    private final Clock clock;

    public DeidentificationService(String pseudonymSecret, Clock clock) {
        this.pseudonymSecret = pseudonymSecret;
        this.clock = clock;
    }

    public DeidentifiedPatient deidentify(PatientRecord patient, String consumerId) {
        return new DeidentifiedPatient(
                pseudonymFor(patient.patientId(), consumerId),
                patient.gender(),
                ageBand(patient.birthYear()),
                List.copyOf(patient.diagnosisCodes()),
                java.util.Map.copyOf(patient.labResults()),
                scrubNotes(patient.notes(), patient.fullName())
        );
    }

    /** 同一 (consumerId, patientId) 永远得到同一假名；换 consumerId 即得不同假名。 */
    public String pseudonymFor(String patientId, String consumerId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    pseudonymSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(
                    (consumerId + ":" + patientId).getBytes(StandardCharsets.UTF_8));
            return "PSD-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private String ageBand(int birthYear) {
        int age = java.time.Year.now(clock).getValue() - birthYear;
        int lower = Math.max(0, age / 10 * 10);
        return lower + "-" + (lower + 9);
    }

    /** 依次处理身份证、手机号、姓名、敏感关键词，避免片段被部分保留。 */
    String scrubNotes(String notes, String fullName) {
        String out = ID_CARD.matcher(notes).replaceAll(REDACTION);
        out = MOBILE.matcher(out).replaceAll(REDACTION);
        if (fullName != null && !fullName.isBlank()) {
            out = out.replace(fullName, REDACTION);
        }
        for (String term : SENSITIVE_TERMS) {
            out = out.replace(term, REDACTION);
        }
        return out;
    }
}
