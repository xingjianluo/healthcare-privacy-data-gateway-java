package com.github.xingjianluo.healthcareprivacydatagatewayjava.service;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessRequest;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessVerdict;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AuditEntry;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.ChainVerificationResult;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.DenyReason;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 审计链：线程安全的追加式哈希链。
 *
 * <p>链式规则（创世记录的 prevHash 为 {@link #GENESIS_HASH}）：
 * <pre>
 * currentHash = SHA256(
 *     prevHash | sequence | entryId | timestamp
 *     | accessorId | patientId | purpose | verdict | denyReason)
 * </pre>
 * 追加在同一把锁内完成"取尾哈希 → 分配序号 → 建条目 → 入链"，
 * 因此并发访问不会重号、不会丢条目；每条 entryId 为 UUID，互不重复。
 *
 * <p>校验时从头重算：序号必须连续、prevHash 必须等于上一条 currentHash、
 * currentHash 必须与重算值一致。任何对历史条目的字段改动、删除或插序
 * 都会导致哈希对不上，校验返回 valid=false 并给出断裂位置。
 */
public class AuditChainService {

    public static final String GENESIS_HASH = "GENESIS";

    private final Clock clock;
    private final List<AuditEntry> chain = new ArrayList<>();
    private final Object lock = new Object();

    public AuditChainService(Clock clock) {
        this.clock = clock;
    }

    /** 追加一条审计记录（成功失败均调用），返回落库的记录。 */
    public AuditEntry append(AccessRequest request, AccessVerdict verdict, DenyReason denyReason) {
        synchronized (lock) {
            long sequence = chain.size();
            String prevHash = sequence == 0 ? GENESIS_HASH : chain.get((int) sequence - 1).currentHash();
            AuditEntry entry = new AuditEntry(
                    sequence,
                    UUID.randomUUID().toString(),
                    clock.instant(),
                    request.accessorId(),
                    request.patientId(),
                    request.purpose() == null ? null : request.purpose().name(),
                    verdict.name(),
                    denyReason == null ? null : denyReason.name(),
                    prevHash,
                    null);
            String currentHash = computeHash(entry);
            AuditEntry stored = new AuditEntry(entry.sequence(), entry.entryId(), entry.timestamp(),
                    entry.accessorId(), entry.patientId(), entry.purpose(),
                    entry.verdict(), entry.denyReason(), entry.prevHash(), currentHash);
            chain.add(stored);
            return stored;
        }
    }

    /** 校验整条链：序号连续、prevHash 衔接、每条 currentHash 重算一致。 */
    public ChainVerificationResult verify() {
        synchronized (lock) {
            long expectedCount = chain.size();
            List<String> broken = new ArrayList<>();
            String expectedPrev = GENESIS_HASH;

            for (int i = 0; i < chain.size(); i++) {
                AuditEntry e = chain.get(i);
                boolean ok = e.sequence() == i
                        && expectedPrev.equals(e.prevHash())
                        && e.currentHash() != null
                        && e.currentHash().equals(computeHash(e));
                if (!ok) {
                    broken.add(String.valueOf(e.sequence()));
                }
                // 即使断裂也继续用记录中的 prevHash 推进，尽可能多报断裂点
                expectedPrev = e.currentHash() == null ? "null" : e.currentHash();
            }

            boolean valid = broken.isEmpty();
            String detail = valid
                    ? "审计链完整，共 " + expectedCount + " 条记录，哈希全部校验通过"
                    : "审计链校验失败，断裂序号: " + broken;
            return new ChainVerificationResult(valid, expectedCount, chain.size(),
                    List.copyOf(broken), detail);
        }
    }

    public List<AuditEntry> entries() {
        synchronized (lock) {
            return List.copyOf(chain);
        }
    }

    public int size() {
        synchronized (lock) {
            return chain.size();
        }
    }

    private static String computeHash(AuditEntry e) {
        String material = String.join("|",
                nullToEmpty(e.prevHash()),
                String.valueOf(e.sequence()),
                nullToEmpty(e.entryId()),
                String.valueOf(e.timestamp()),
                nullToEmpty(e.accessorId()),
                nullToEmpty(e.patientId()),
                nullToEmpty(e.purpose()),
                nullToEmpty(e.verdict()),
                nullToEmpty(e.denyReason()));
        return PseudonymService.sha256Hex(material);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
