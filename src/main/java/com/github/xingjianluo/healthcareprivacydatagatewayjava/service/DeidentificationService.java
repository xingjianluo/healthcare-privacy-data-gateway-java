package com.github.xingjianluo.healthcareprivacydatagatewayjava.service;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.DeidentifiedRecord;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.PatientRecord;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 去标识化。
 *
 * <p>口径（字段白名单 + 文本掩码，双保险）：
 * <ol>
 *   <li>返回字段采用白名单：仅 pseudonym、gender、diagnoses、labResults、
 *       clinicalNoteMasked。name/idCard/phone/birthDate/patientId 等直接标识符
 *       根本不会被拷贝到结果中；</li>
 *   <li>诊断编码、检验结果等科研结构化字段原样保留，不经过文本处理，避免误删；</li>
 *   <li>自由文本按顺序掩码：患者姓名 → 身份证号（18位）→ 手机号（11位）→
 *       出生日期/日期 → 地址片段（"家住…/住址…/地址…"）。
 *       先处理身份证再处理手机号，避免长数字串被短规则部分误匹配。</li>
 * </ol>
 */
public class DeidentificationService {

    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[\\dXx]");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ADDRESS =
            Pattern.compile("((?:家住|住址为?|地址为?)[：:]?)[^，。；;、]*");

    private final PseudonymService pseudonymService;

    public DeidentificationService(PseudonymService pseudonymService) {
        this.pseudonymService = pseudonymService;
    }

    public DeidentifiedRecord deidentify(PatientRecord source, String accessorId) {
        String pseudonym = pseudonymService.pseudonymFor(accessorId, source.patientId());
        String maskedNote = maskFreeText(source.clinicalNote(), source);

        // 防御性拷贝，返回结果与可变源数据解耦
        return new DeidentifiedRecord(
                pseudonym,
                source.gender(),
                List.copyOf(source.diagnoses()),
                Map.copyOf(source.labResults()),
                maskedNote);
    }

    private String maskFreeText(String note, PatientRecord source) {
        String text = note;
        if (source.name() != null && !source.name().isBlank()) {
            text = text.replace(source.name(), "[NAME]");
        }
        text = ID_CARD.matcher(text).replaceAll("[ID_CARD]");
        text = PHONE.matcher(text).replaceAll("[PHONE]");
        if (source.birthDate() != null && !source.birthDate().isBlank()) {
            text = text.replace(source.birthDate(), "[BIRTH_DATE]");
        }
        text = DATE.matcher(text).replaceAll("[DATE]");
        text = ADDRESS.matcher(text).replaceAll("$1[ADDRESS]");
        return text;
    }
}
