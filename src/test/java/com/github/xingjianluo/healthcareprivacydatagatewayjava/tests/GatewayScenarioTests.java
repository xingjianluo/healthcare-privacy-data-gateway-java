package com.github.xingjianluo.healthcareprivacydatagatewayjava.tests;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessPurpose;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessRequest;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessResult;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.DenialReason;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.PatientRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 授权判定与去标识化口径的场景测试。 */
class GatewayScenarioTests {

    private static final Logger log = LoggerFactory.getLogger(GatewayScenarioTests.class);

    private AccessResult call(GatewayTestSupport.TestContext ctx,
                              String patient, String consumer, AccessPurpose purpose) {
        AccessRequest req = new AccessRequest(patient, consumer, purpose);
        AccessResult result = ctx.gateway().access(req);
        log.info("访问输入 patientId={}, consumerId={}, purpose={} => 判定 allowed={}, reason={}, requestId={}",
                patient, consumer, purpose, result.allowed(), result.denialReason(), result.requestId());
        return result;
    }

    @Test
    @DisplayName("有效授权 + 匹配用途：允许并返回去标识化最小字段")
    void validConsentAllowsResearchAccess() {
        var ctx = GatewayTestSupport.newContext();
        AccessResult result = call(ctx, "P001", "C001", AccessPurpose.RESEARCH);

        assertTrue(result.allowed());
        assertNull(result.denialReason());
        assertEquals("PSD-", result.data().pseudonym().substring(0, 4));
        assertEquals("40-49", result.data().ageBand(), "1985 年出生在 2026 年应泛化为 40-49 段");

        PatientRecord raw = ctx.store().findPatient("P001").orElseThrow();
        String serialized = result.data().toString();
        assertFalse(serialized.contains(raw.fullName()), "姓名不得出现在返回中");
        assertFalse(serialized.contains(raw.idCardNo()), "身份证号不得出现在返回中");
        assertFalse(serialized.contains(raw.phone()), "手机号不得出现在返回中");

        assertTrue(result.data().diagnosisCodes().contains("I21.4"),
                "科研所需结构化诊断编码必须保留");
        assertEquals(7.2, result.data().labResults().get("glucose_fasting"),
                "科研所需结构化检验结果必须保留");

        assertFalse(result.data().scrubbedNotes().contains("13800010001"), "文本中的手机号必须脱敏");
        assertFalse(result.data().scrubbedNotes().contains("张伟"), "文本中的姓名必须脱敏");
        assertFalse(result.data().scrubbedNotes().contains("HIV"), "文本中的敏感片段必须脱敏");
        assertTrue(result.data().scrubbedNotes().contains("[脱敏]"),
                "敏感片段应以占位符替换");
    }

    @Test
    @DisplayName("授权已撤回：明确返回 CONSENT_REVOKED，与其它原因可区分")
    void revokedConsentIsDeniedWithDistinctReason() {
        var ctx = GatewayTestSupport.newContext();
        AccessResult result = call(ctx, "P001", "C001", AccessPurpose.TREATMENT);
        assertFalse(result.allowed());
        assertEquals(DenialReason.CONSENT_REVOKED, result.denialReason());
    }

    @Test
    @DisplayName("授权已过期：明确返回 CONSENT_EXPIRED")
    void expiredConsentIsDeniedWithDistinctReason() {
        var ctx = GatewayTestSupport.newContext();
        AccessResult result = call(ctx, "P002", "C001", AccessPurpose.RESEARCH);
        assertFalse(result.allowed());
        assertEquals(DenialReason.CONSENT_EXPIRED, result.denialReason());
    }

    @Test
    @DisplayName("用途超出授权范围：明确返回 PURPOSE_OUT_OF_SCOPE")
    void outOfScopePurposeIsDeniedWithDistinctReason() {
        var ctx = GatewayTestSupport.newContext();
        AccessResult result = call(ctx, "P001", "C002", AccessPurpose.RESEARCH);
        assertFalse(result.allowed());
        assertEquals(DenialReason.PURPOSE_OUT_OF_SCOPE, result.denialReason());
    }

    @Test
    @DisplayName("访问方未获得任何授权：明确返回 NO_CONSENT")
    void unconsentedConsumerIsDeniedWithDistinctReason() {
        var ctx = GatewayTestSupport.newContext();
        AccessResult result = call(ctx, "P001", "C003", AccessPurpose.RESEARCH);
        assertFalse(result.allowed());
        assertEquals(DenialReason.NO_CONSENT, result.denialReason());
    }

    @Test
    @DisplayName("访问方/患者不存在：返回各自独立的原因码")
    void unknownPartiesHaveDistinctReasons() {
        var ctx = GatewayTestSupport.newContext();
        assertEquals(DenialReason.CONSUMER_NOT_FOUND,
                call(ctx, "P001", "C999", AccessPurpose.RESEARCH).denialReason());
        assertEquals(DenialReason.PATIENT_NOT_FOUND,
                call(ctx, "P999", "C001", AccessPurpose.RESEARCH).denialReason());
    }

    @Test
    @DisplayName("假名：同一访问方稳定可复现，不同访问方之间不可关联")
    void pseudonymIsStableWithinConsumerButUnlinkableAcrossConsumers() {
        var ctx = GatewayTestSupport.newContext();
        String c001First = ctx.deid().pseudonymFor("P002", "C001");
        String c001Second = ctx.deid().pseudonymFor("P002", "C001");
        String c002 = ctx.deid().pseudonymFor("P002", "C002");
        log.info("假名对比 P002/C001 第一次={}, 第二次={}, P002/C002={}", c001First, c001Second, c002);

        assertEquals(c001First, c001Second, "同一访问方同一患者假名必须稳定可复现");
        assertNotEquals(c001First, c002, "不同访问方假名必须不同，无法跨方关联");
    }

    @Test
    @DisplayName("判定失败也必须留痕")
    void deniedRequestIsAlsoAudited() {
        var ctx = GatewayTestSupport.newContext();
        call(ctx, "P001", "C001", AccessPurpose.TREATMENT);
        assertEquals(1, ctx.audit().entries().size());
        assertEquals("DENY", ctx.audit().entries().get(0).decision());
        assertEquals("CONSENT_REVOKED", ctx.audit().entries().get(0).denialReason());
        assertTrue(ctx.audit().verify().valid());
    }
}
