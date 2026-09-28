package com.github.xingjianluo.healthcareprivacydatagatewayjava.tests;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessRequest;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessResult;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessVerdict;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AuditEntry;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.ChainVerificationResult;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.DenyReason;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.Purpose;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.repository.LocalDataRepository;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.AuditChainService;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.AuthorizationPolicyService;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.DeidentificationService;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.PatientDataAccessService;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.service.PseudonymService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 患者数据访问接口的核心场景测试。
 *
 * <p>不启动 Spring 容器，直接以固定 Clock（2026-09-28T00:00:00Z）手动装配各服务，
 * 使过期/有效判定稳定可复现。每个用例都打印关键输入与判定结果。
 */
class PatientDataAccessTest {

    private static final Logger log = LoggerFactory.getLogger(PatientDataAccessTest.class);
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");

    private LocalDataRepository repository;
    private AuditChainService auditChain;
    private PatientDataAccessService service;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);
        repository = new LocalDataRepository();
        auditChain = new AuditChainService(fixedClock);
        PseudonymService pseudonymService = new PseudonymService();
        DeidentificationService deidentificationService = new DeidentificationService(pseudonymService);
        AuthorizationPolicyService policyService = new AuthorizationPolicyService(fixedClock);
        service = new PatientDataAccessService(repository, policyService,
                deidentificationService, auditChain);
    }

    /** 打印关键输入与判定结果，便于人工核对。 */
    private AccessResult invoke(String caseName, AccessRequest req) {
        AccessResult result = service.access(req);
        log.info("[{}] 输入: accessorId={}, patientId={}, purpose={} => 判定: {}, 拒绝原因: {}, 审计ID: {}",
                caseName, req.accessorId(), req.patientId(), req.purpose(),
                result.verdict(), result.denyReason(), result.auditEntryId());
        return result;
    }

    @Test
    void allowForValidGrant() {
        // G-001：P-001 授权 ORG-A 科研，2026-09-28 时有效
        AccessResult r = invoke("允许-有效授权",
                new AccessRequest(LocalDataRepository.ORG_A, LocalDataRepository.PATIENT_001, Purpose.RESEARCH));
        assertEquals(AccessVerdict.ALLOW, r.verdict());
        assertNull(r.denyReason());
        assertNotNull(r.record());
        assertNotNull(r.auditEntryId());
    }

    @Test
    void denyUnknownAccessor() {
        AccessResult r = invoke("拒绝-未知访问方",
                new AccessRequest("ORG-UNKNOWN", LocalDataRepository.PATIENT_001, Purpose.RESEARCH));
        assertDeny(r, DenyReason.UNKNOWN_ACCESSOR);
    }

    @Test
    void denyNoGrant() {
        // ORG-X 已登记，但 P-001 从未向它授权
        AccessResult r = invoke("拒绝-无授权",
                new AccessRequest(LocalDataRepository.ORG_X, LocalDataRepository.PATIENT_001, Purpose.RESEARCH));
        assertDeny(r, DenyReason.NO_GRANT);
    }

    @Test
    void denyGrantExpired() {
        // G-002：P-001 对 ORG-B 的授权 2025-01-01 已过期
        AccessResult r = invoke("拒绝-授权过期",
                new AccessRequest(LocalDataRepository.ORG_B, LocalDataRepository.PATIENT_001, Purpose.RESEARCH));
        assertDeny(r, DenyReason.GRANT_EXPIRED);
    }

    @Test
    void denyPurposeOutOfScope() {
        // G-003：P-002 仅授权 ORG-A 临床试验，科研用途超范围
        AccessResult r = invoke("拒绝-用途超范围",
                new AccessRequest(LocalDataRepository.ORG_A, LocalDataRepository.PATIENT_002, Purpose.RESEARCH));
        assertDeny(r, DenyReason.PURPOSE_OUT_OF_SCOPE);
    }

    @Test
    void denyReasonsAreDistinct() {
        // 同一组场景下失败原因两两不同，不允许混成同一种报错
        DenyReason unknown = invoke("区分-未知访问方",
                new AccessRequest("ORG-UNKNOWN", LocalDataRepository.PATIENT_001, Purpose.RESEARCH)).denyReason();
        DenyReason noGrant = invoke("区分-无授权",
                new AccessRequest(LocalDataRepository.ORG_X, LocalDataRepository.PATIENT_001, Purpose.RESEARCH)).denyReason();
        DenyReason expired = invoke("区分-过期",
                new AccessRequest(LocalDataRepository.ORG_B, LocalDataRepository.PATIENT_001, Purpose.RESEARCH)).denyReason();
        DenyReason revoked = invoke("区分-撤回",
                new AccessRequest(LocalDataRepository.ORG_B, LocalDataRepository.PATIENT_002, Purpose.RESEARCH)).denyReason();
        DenyReason scope = invoke("区分-超范围",
                new AccessRequest(LocalDataRepository.ORG_A, LocalDataRepository.PATIENT_002, Purpose.RESEARCH)).denyReason();

        long distinct = java.util.stream.Stream.of(unknown, noGrant, expired, revoked, scope).distinct().count();
        log.info("[拒绝原因区分性] 五种原因: {}, {}, {}, {}, {}；去重后数量={}",
                unknown, noGrant, expired, revoked, scope, distinct);
        assertEquals(5, distinct, "五类失败必须对应五个可区分的拒绝原因");
    }

    private static void assertDeny(AccessResult r, DenyReason expected) {
        assertEquals(AccessVerdict.DENY, r.verdict());
        assertEquals(expected, r.denyReason(), "拒绝原因必须为 " + expected);
        assertNull(r.record(), "拒绝时不得返回任何患者数据");
        assertNotNull(r.message());
        assertNotNull(r.auditEntryId(), "被拒绝的请求也必须留下审计记录");
    }

    @Test
    void revokeTakesEffectImmediatelyAndIsDistinct() {
        // 选取当前 ACTIVE 的 G-001（ORG-A / P-001 / RESEARCH）做撤回演示：
        AccessRequest g001 = new AccessRequest(
                LocalDataRepository.ORG_A, LocalDataRepository.PATIENT_001, Purpose.RESEARCH);
        AccessResult before = invoke("撤回-撤回前访问", g001);
        assertEquals(AccessVerdict.ALLOW, before.verdict(), "撤回前应允许访问");

        boolean revoked = repository.revokeGrant("G-001");
        assertTrue(revoked, "G-001 应撤回成功");
        log.info("[授权撤回] G-001 撤回操作完成, revoked={}", revoked);

        AccessResult after = invoke("撤回-撤回后访问", g001);
        assertDeny(after, DenyReason.GRANT_REVOKED);
        log.info("[授权撤回] 撤回后返回信息: {}", after.message());

        // 重复撤回不会再次生效
        assertFalse(repository.revokeGrant("G-001"), "重复撤回应返回 false");
        // 被撤回的授权不应因为其他原因（如过期/超范围）报错，原因必须仍是撤回
        assertDeny(invoke("撤回-再次访问", g001), DenyReason.GRANT_REVOKED);
    }

    @Test
    void pseudonymStableWithinAccessorAndDifferentAcrossAccessors() {
        // 同一访问方对同一患者两次访问：假名一致（可复现）
        AccessRequest a1 = new AccessRequest(
                LocalDataRepository.ORG_A, LocalDataRepository.PATIENT_001, Purpose.RESEARCH);
        AccessRequest a2 = new AccessRequest(
                LocalDataRepository.ORG_A, LocalDataRepository.PATIENT_001, Purpose.CLINICAL_TRIAL);
        String p1 = invoke("假名-ORG-A第一次", a1).record().pseudonym();
        String p2 = invoke("假名-ORG-A第二次", a2).record().pseudonym();
        assertEquals(p1, p2, "同一访问方同一患者的假名必须稳定可复现");

        // 另一个访问方对同一患者：假名必须不同，无法跨机构关联
        // ORG-B 对 P-001 的授权已过期，无法走正常放行路径；
        // 直接用 PseudonymService 验证跨访问方隔离口径。
        PseudonymService pseudonymService = new PseudonymService();
        String forA = pseudonymService.pseudonymFor(LocalDataRepository.ORG_A, LocalDataRepository.PATIENT_001);
        String forB = pseudonymService.pseudonymFor(LocalDataRepository.ORG_B, LocalDataRepository.PATIENT_001);
        log.info("[假名隔离] ORG-A假名={}, ORG-B假名={}", forA, forB);
        assertNotEquals(forA, forB, "不同访问方的假名必须不同，不能互相关联");
        assertEquals(forA, p1, "服务返回假名与口径计算一致");
        assertFalse(forA.contains(LocalDataRepository.PATIENT_001), "假名中不得出现真实患者ID");
    }

    @Test
    void deidentificationRemovesDirectIdentifiersAndMasksFreeText() {
        AccessResult r = invoke("去标识化",
                new AccessRequest(LocalDataRepository.ORG_A, LocalDataRepository.PATIENT_001, Purpose.RESEARCH));
        String json = r.record().toString();
        log.info("[去标识化] 返回记录: {}", json);
        log.info("[去标识化] 掩码后临床文本: {}", r.record().clinicalNoteMasked());

        // 直接标识符不得出现在返回内容的任何位置（含 toString 序列化视角）
        assertFalse(json.contains("张三"), "姓名必须移除");
        assertFalse(json.contains("110101199003078888"), "身份证号必须移除");
        assertFalse(json.contains("13800138001"), "手机号必须移除");
        assertFalse(json.contains("1990-03-07"), "出生日期必须移除");
        assertFalse(json.contains("P-001"), "真实患者ID必须移除");

        String note = r.record().clinicalNoteMasked();
        assertTrue(note.contains("[NAME]"), "自由文本中的姓名应被掩码");
        assertTrue(note.contains("[ID_CARD]"), "自由文本中的身份证号应被掩码");
        assertTrue(note.contains("[PHONE]"), "自由文本中的手机号应被掩码");
        assertTrue(note.contains("[ADDRESS]"), "自由文本中的地址片段应被掩码");

        // 科研需要的结构化字段必须原样保留，不能被误删
        assertEquals("M", r.record().gender());
        assertTrue(r.record().diagnoses().contains("E11.9"), "诊断编码必须保留");
        assertTrue(r.record().diagnoses().contains("I10"), "诊断编码必须保留");
        assertEquals("7.2%", r.record().labResults().get("HbA1c"), "检验结果必须保留");
        assertEquals("142 mmHg", r.record().labResults().get("SBP"), "检验结果必须保留");
        // 临床含义文本保留
        assertTrue(note.contains("二甲双胍"), "临床用药等非敏感文本应保留");
    }

    @Test
    void deniedRequestsAlsoLeaveAuditTrailAndChainVerifies() {
        invoke("审计-允许", new AccessRequest(
                LocalDataRepository.ORG_A, LocalDataRepository.PATIENT_001, Purpose.RESEARCH));
        invoke("审计-过期拒绝", new AccessRequest(
                LocalDataRepository.ORG_B, LocalDataRepository.PATIENT_001, Purpose.RESEARCH));
        invoke("审计-未知拒绝", new AccessRequest(
                "ORG-UNKNOWN", LocalDataRepository.PATIENT_001, Purpose.RESEARCH));

        ChainVerificationResult verification = auditChain.verify();
        log.info("[审计链] 条目数={}, 校验结果={}, 详情={}",
                auditChain.size(), verification.valid(), verification.detail());

        assertEquals(3, auditChain.size(), "成功与失败的请求都必须留痕，一条不能少");
        assertTrue(verification.valid(), "未篡改的链必须校验通过");
        // 链上应同时存在 ALLOW 与 DENY
        long allowCount = auditChain.entries().stream()
                .filter(e -> e.verdict().equals(AccessVerdict.ALLOW.name())).count();
        long denyCount = auditChain.entries().stream()
                .filter(e -> e.verdict().equals(AccessVerdict.DENY.name())).count();
        assertEquals(1, allowCount);
        assertEquals(2, denyCount);
    }

    @Test
    void concurrentAccessProducesCompleteOrderedUniqueChain() throws Exception {
        int threads = 8;
        int perThread = 25;
        int total = threads * perThread;

        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final boolean allowed = (t % 2 == 0);
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        if (allowed) {
                            service.access(new AccessRequest(LocalDataRepository.ORG_A,
                                    LocalDataRepository.PATIENT_001, Purpose.RESEARCH));
                        } else {
                            // 交替不同失败原因，验证并发失败请求同样入链
                            DenyReason pick = (i % 2 == 0) ? DenyReason.GRANT_EXPIRED
                                    : DenyReason.UNKNOWN_ACCESSOR;
                            String accessor = pick == DenyReason.GRANT_EXPIRED
                                    ? LocalDataRepository.ORG_B : "ORG-UNKNOWN";
                            service.access(new AccessRequest(accessor,
                                    LocalDataRepository.PATIENT_001, Purpose.RESEARCH));
                        }
                    }
                }));
            }
            for (var f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }

        log.info("[并发审计] 期望条目={}, 实际条目={}", total, auditChain.size());
        assertEquals(total, auditChain.size(), "并发访问不得丢记录");

        java.util.List<AuditEntry> entries = auditChain.entries();

        // 序号从 0 连续且不重复
        for (int i = 0; i < total; i++) {
            assertEquals(i, entries.get(i).sequence(), "序号必须连续不重号");
        }
        // entryId 全局唯一
        long uniqueIds = entries.stream().map(AuditEntry::entryId).distinct().count();
        assertEquals(total, uniqueIds, "审计条目ID不得重复");
        // 哈希衔接
        for (int i = 1; i < total; i++) {
            assertEquals(entries.get(i - 1).currentHash(), entries.get(i).prevHash(),
                    "相邻记录哈希必须首尾衔接");
        }
        // 允许/失败数量与构造的负载一致
        assertEquals(threads / 2 * perThread,
                entries.stream().filter(e -> e.verdict().equals("ALLOW")).count());
        assertEquals(threads / 2 * perThread,
                entries.stream().filter(e -> e.verdict().equals("DENY")).count());

        ChainVerificationResult verification = auditChain.verify();
        log.info("[并发审计] 校验结果={}, 详情={}", verification.valid(), verification.detail());
        assertTrue(verification.valid(), "高并发追加后链仍必须完整可校验");
    }

    @Test
    void tamperedChainIsDetected() throws Exception {
        invoke("篡改-允许", new AccessRequest(
                LocalDataRepository.ORG_A, LocalDataRepository.PATIENT_001, Purpose.RESEARCH));
        invoke("篡改-拒绝", new AccessRequest(
                LocalDataRepository.ORG_B, LocalDataRepository.PATIENT_001, Purpose.RESEARCH));
        invoke("篡改-允许2", new AccessRequest(
                LocalDataRepository.ORG_A, LocalDataRepository.PATIENT_001, Purpose.CLINICAL_TRIAL));
        assertTrue(auditChain.verify().valid());

        // 模拟有人事后篡改第 2 条记录的判定结果（DENY -> ALLOW）：
        // 通过反射替换链中元素，模拟存储层被改动
        java.lang.reflect.Field chainField = AuditChainService.class.getDeclaredField("chain");
        chainField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<AuditEntry> chain = (java.util.List<AuditEntry>) chainField.get(auditChain);
        AuditEntry original = chain.get(1);
        AuditEntry forged = new AuditEntry(original.sequence(), original.entryId(), original.timestamp(),
                original.accessorId(), original.patientId(), original.purpose(),
                AccessVerdict.ALLOW.name(), null, // 把 DENY/GRANT_EXPIRED 改成 ALLOW
                original.prevHash(), original.currentHash());
        chain.set(1, forged);

        ChainVerificationResult result = auditChain.verify();
        log.info("[篡改检测] valid={}, 断裂序号={}, 详情={}",
                result.valid(), result.brokenAtSequences(), result.detail());
        assertFalse(result.valid(), "被篡改的链必须校验失败");
        // 篡改保留了原 currentHash，因此只有被改条目自身重算哈希不匹配；
        // 这足以明确发现篡改并定位到序号 1
        assertEquals(List.of("1"), result.brokenAtSequences(),
                "必须明确定位到被篡改的序号 1");
    }

    @Test
    void tamperedHashFieldIsDetectedAndCascades() throws Exception {
        for (int i = 0; i < 3; i++) {
            invoke("哈希篡改-" + i, new AccessRequest(
                    LocalDataRepository.ORG_A, LocalDataRepository.PATIENT_001, Purpose.RESEARCH));
        }
        // 攻击者不仅改字段，还重造了该条的 currentHash 试图自洽
        java.lang.reflect.Field chainField = AuditChainService.class.getDeclaredField("chain");
        chainField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<AuditEntry> chain = (java.util.List<AuditEntry>) chainField.get(auditChain);
        AuditEntry original = chain.get(0);
        AuditEntry forged = new AuditEntry(original.sequence(), original.entryId(), original.timestamp(),
                original.accessorId(), original.patientId(), original.purpose(),
                original.verdict(), original.denyReason(),
                AuditChainService.GENESIS_HASH, "forged-hash-value");
        chain.set(0, forged);

        ChainVerificationResult result = auditChain.verify();
        log.info("[哈希伪造检测] valid={}, 断裂序号={}, 详情={}",
                result.valid(), result.brokenAtSequences(), result.detail());
        assertFalse(result.valid());
        // 序号0伪造哈希，序号1的 prevHash 必然对不上 -> 级联暴露
        assertTrue(result.brokenAtSequences().contains("0"));
        assertTrue(result.brokenAtSequences().contains("1"));
    }

    @Test
    void placeholder() {
        assertTrue(true);
    }
}
