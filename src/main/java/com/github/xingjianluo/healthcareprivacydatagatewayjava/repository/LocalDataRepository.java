package com.github.xingjianluo.healthcareprivacydatagatewayjava.repository;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.Accessor;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.Grant;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.GrantStatus;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.PatientRecord;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.Purpose;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地模拟数据：患者记录、访问方、授权。
 *
 * <p>数据在构造时加载到内存。授权撤回通过替换内存中 Grant 的状态来模拟，
 * 因此撤回后后续访问立即生效。所有时间均为绝对时间，授权是否过期由
 * {@code AuthorizationPolicyService} 结合注入的 Clock 判定，
 * 默认基准日期为 2026-09-28。
 */
public class LocalDataRepository {

    /** 患者ID常量，便于测试引用。 */
    public static final String PATIENT_001 = "P-001";
    public static final String PATIENT_002 = "P-002";

    /** 已登记的访问方。 */
    public static final String ORG_A = "ORG-A"; // 大学基因组研究中心
    public static final String ORG_B = "ORG-B"; // 市立医院科研处
    public static final String ORG_X = "ORG-X"; // 已登记但没有任何授权的机构

    private final Map<String, PatientRecord> patients = new ConcurrentHashMap<>();
    private final Map<String, Accessor> accessors = new ConcurrentHashMap<>();
    /** key = patientId + "|" + accessorId */
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    public LocalDataRepository() {
        loadSeedData();
    }

    public Optional<PatientRecord> findPatient(String patientId) {
        return Optional.ofNullable(patients.get(patientId));
    }

    public Optional<Accessor> findAccessor(String accessorId) {
        return Optional.ofNullable(accessors.get(accessorId));
    }

    /** 查找某患者对某访问方的授权（无论状态，含已撤回/已过期）。 */
    public Optional<Grant> findGrant(String patientId, String accessorId) {
        return Optional.ofNullable(grants.get(grantKey(patientId, accessorId)));
    }

    /** 按授权ID撤回，返回是否找到并撤回成功（重复撤回返回 false）。 */
    public boolean revokeGrant(String grantId) {
        boolean[] changed = {false};
        grants.replaceAll((key, grant) -> {
            if (grant.grantId().equals(grantId) && grant.status() == GrantStatus.ACTIVE) {
                changed[0] = true;
                return new Grant(grant.grantId(), grant.patientId(), grant.accessorId(),
                        grant.allowedPurposes(), GrantStatus.REVOKED,
                        grant.grantedAt(), grant.expiresAt(), Instant.parse("2026-09-28T00:00:00Z"));
            }
            return grant;
        });
        return changed[0];
    }

    public List<Grant> allGrants() {
        return List.copyOf(grants.values());
    }

    private static String grantKey(String patientId, String accessorId) {
        return patientId + "|" + accessorId;
    }

    private void loadSeedData() {
        patients.put(PATIENT_001, new PatientRecord(
                PATIENT_001,
                "张三",
                "110101199003078888",
                "13800138001",
                "1990-03-07",
                "M",
                List.of("E11.9", "I10"),
                new ConcurrentHashMap<>(Map.of(
                        "HbA1c", "7.2%",
                        "LDL", "3.4 mmol/L",
                        "SBP", "142 mmHg")),
                "患者张三（电话13800138001，身份证110101199003078888）"
                        + "主诉口干多尿三月余，既往2型糖尿病，医嘱二甲双胍随餐服用，"
                        + "家住北京市海淀区中关村大街1号需定期随访。"));

        patients.put(PATIENT_002, new PatientRecord(
                PATIENT_002,
                "李四",
                "310104198812256666",
                "13912345678",
                "1988-12-25",
                "F",
                List.of("J45.909"),
                new ConcurrentHashMap<>(Map.of(
                        "FEV1", "2.6 L",
                        "IgE", "180 IU/mL")),
                "患者李四自述近一周夜间喘息，联系电话13912345678；"
                        + "过敏原检测提示尘螨阳性，建议避免接触并规律吸入布地奈德。"));

        accessors.put(ORG_A, new Accessor(ORG_A, "大学基因组研究中心"));
        accessors.put(ORG_B, new Accessor(ORG_B, "市立医院科研处"));
        accessors.put(ORG_X, new Accessor(ORG_X, "某商业分析公司"));

        // G-001：P-001 授权 ORG-A 科研/临床试验，有效期到 2027 年（当前有效）
        putGrant(new Grant("G-001", PATIENT_001, ORG_A,
                Set.of(Purpose.RESEARCH, Purpose.CLINICAL_TRIAL),
                GrantStatus.ACTIVE,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-12-31T23:59:59Z"),
                null));

        // G-002：P-001 授权 ORG-B 科研，但 2025 年已过期
        putGrant(new Grant("G-002", PATIENT_001, ORG_B,
                Set.of(Purpose.RESEARCH),
                GrantStatus.ACTIVE,
                Instant.parse("2023-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"),
                null));

        // G-003：P-002 仅授权 ORG-A 临床试验（用 RESEARCH 访问即用途超范围）
        putGrant(new Grant("G-003", PATIENT_002, ORG_A,
                Set.of(Purpose.CLINICAL_TRIAL),
                GrantStatus.ACTIVE,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-12-31T23:59:59Z"),
                null));

        // G-004：P-002 授权 ORG-B 科研，已于 2026-09-01 撤回
        putGrant(new Grant("G-004", PATIENT_002, ORG_B,
                Set.of(Purpose.RESEARCH),
                GrantStatus.REVOKED,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-12-31T23:59:59Z"),
                Instant.parse("2026-09-01T10:00:00Z")));
    }

    private void putGrant(Grant grant) {
        grants.put(grantKey(grant.patientId(), grant.accessorId()), grant);
    }
}
