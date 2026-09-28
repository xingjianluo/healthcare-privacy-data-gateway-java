package com.github.xingjianluo.healthcareprivacydatagatewayjava.service;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessPurpose;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessRequest;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AuditEntry;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AuditVerificationResult;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.DenialReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 审计哈希链校验测试。 */
class AuditChainTests {

    private static final Logger log = LoggerFactory.getLogger(AuditChainTests.class);

    private AuditChainService newChain() {
        return new AuditChainService(Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"),
                ZoneOffset.UTC));
    }

    @Test
    @DisplayName("正常追加后审计链校验通过：序号连续、哈希首尾衔接")
    void intactChainVerifies() {
        AuditChainService chain = newChain();
        chain.append("req-1", new AccessRequest("P001", "C001", AccessPurpose.RESEARCH),
                true, null);
        chain.append("req-2", new AccessRequest("P002", "C001", AccessPurpose.RESEARCH),
                false, DenialReason.CONSENT_EXPIRED);

        AuditVerificationResult result = chain.verify();
        log.info("正常链校验结果 valid={}, message={}", result.valid(), result.message());
        assertTrue(result.valid(), result.message());

        assertEquals(2, chain.entries().size());
        assertEquals(1, chain.entries().get(0).sequence());
        assertEquals(2, chain.entries().get(1).sequence());
        assertEquals(chain.entries().get(0).entryHash(),
                chain.entries().get(1).previousHash(), "第二条必须衔接第一条的哈希");
        assertEquals(AuditChainService.GENESIS_HASH,
                chain.entries().get(0).previousHash());
    }

    @Test
    @DisplayName("篡改任一条目的判定结论：校验在断裂处明确失败")
    void tamperedEntryIsDetectedAtExactSequence() {
        AuditChainService chain = newChain();
        for (int i = 1; i <= 3; i++) {
            chain.append("req-" + i, new AccessRequest("P00" + i, "C001",
                    AccessPurpose.RESEARCH), true, null);
        }
        assertTrue(chain.verify().valid());

        String originalHash = chain.entries().get(1).entryHash();
        chain.tamperDecisionForTesting(2, "DENY");
        AuditVerificationResult result = chain.verify();
        log.info("篡改第2条后校验结果 valid={}, brokenAt={}, message={}",
                result.valid(), result.brokenAtSequence(), result.message());

        assertFalse(result.valid());
        assertEquals(2L, result.brokenAtSequence(), "必须定位到首个被改动的序号");
        AuditEntry tampered = chain.entries().get(1);
        assertEquals("DENY", tampered.decision());
        assertNotEquals(originalHash,
                "校验依据是重算哈希与记录哈希不一致", tampered.entryHash());
    }
}
