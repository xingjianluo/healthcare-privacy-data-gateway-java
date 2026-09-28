package com.github.xingjianluo.healthcareprivacydatagatewayjava.store;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessPurpose;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.Consent;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.ConsentStatus;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.Consumer;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.PatientRecord;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 本地模拟数据仓库：患者记录、授权、访问方全部为内存种子数据。
 *
 * <p>种子数据围绕固定基准日 2026-09-28 构造，确保判定结论稳定可复现：
 * <ul>
 *   <li>CS001 P001->C001 RESEARCH 有效</li>
 *   <li>CS002 P001->C001 TREATMENT 已撤回</li>
 *   <li>CS003 P002->C001 RESEARCH 已过期</li>
 *   <li>CS004 P001->C002 TREATMENT 有效（C002 以 RESEARCH 访问即越权用途）</li>
 *   <li>CS005 P002->C002 RESEARCH 有效</li>
 *   <li>C003 对任何患者都没有授权（未授权访问方）</li>
 * </ul>
 */
public class LocalDataStore {

    private static final Instant T2025 = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant T2026 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T2026_08 = Instant.parse("2026-08-31T23:59:59Z");
    private static final Instant T2027 = Instant.parse("2027-01-01T00:00:00Z");

    private final Map<String, PatientRecord> patients = Map.of(
            "P001", new PatientRecord(
                    "P001", "张伟", "110101198503120011", "13800010001",
                    "M", 1985,
                    List.of("I21.4", "E11.9"),
                    Map.of("glucose_fasting", 7.2, "ldl_cholesterol", 3.4, "systolic_bp", 142.0),
                    "患者张伟（联系电话13800010001）自述胸痛2小时。既往糖尿病史，"
                            + "随访提示HIV抗体待复查，否认近期手术史。"),
            "P002", new PatientRecord(
                    "P002", "李娜", "110101199007070022", "13800010002",
                    "F", 1990,
                    List.of("C50.9"),
                    Map.of("ca153", 28.5, "hemoglobin", 121.0),
                    "患者李娜，身份证号110101199007070022，病理确诊乳腺癌，"
                            + "化疗后复诊，既往有精神分裂症住院史。"),
            "P003", new PatientRecord(
                    "P003", "王强", "110101199212050033", "13800010003",
                    "M", 1992,
                    List.of("J45.909"),
                    Map.of("fev1", 2.9, "ige", 168.0),
                    "患者王强（手机13800010003），支气管哮喘规律吸入治疗，症状控制可。")
    );

    private final Map<String, Consumer> consumers = Map.of(
            "C001", new Consumer("C001", "北京医学科学研究院", "跨机构科研共享课题组"),
            "C002", new Consumer("C002", "某商业分析有限公司", "商业数据合作部"),
            "C003", new Consumer("C003", "区域公共卫生数据中心", "流行病学监测室")
    );

    private final List<Consent> consents = List.of(
            new Consent("CS001", "P001", "C001",
                    Set.of(AccessPurpose.RESEARCH), ConsentStatus.ACTIVE, T2026, T2027),
            new Consent("CS002", "P001", "C001",
                    Set.of(AccessPurpose.TREATMENT), ConsentStatus.REVOKED, T2026, T2027),
            new Consent("CS003", "P002", "C001",
                    Set.of(AccessPurpose.RESEARCH), ConsentStatus.ACTIVE, T2025, T2026_08),
            new Consent("CS004", "P001", "C002",
                    Set.of(AccessPurpose.TREATMENT), ConsentStatus.ACTIVE, T2026, T2027),
            new Consent("CS005", "P002", "C002",
                    Set.of(AccessPurpose.RESEARCH), ConsentStatus.ACTIVE, T2026, T2027)
    );

    public Optional<PatientRecord> findPatient(String patientId) {
        return Optional.ofNullable(patients.get(patientId));
    }

    public Optional<Consumer> findConsumer(String consumerId) {
        return Optional.ofNullable(consumers.get(consumerId));
    }

    public List<Consent> findConsents(String patientId, String consumerId) {
        return consents.stream()
                .filter(c -> c.patientId().equals(patientId) && c.consumerId().equals(consumerId))
                .toList();
    }
}
