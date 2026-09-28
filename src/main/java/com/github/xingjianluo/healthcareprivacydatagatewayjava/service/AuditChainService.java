package com.github.xingjianluo.healthcareprivacydatagatewayjava.service;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessRequest;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AuditEntry;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AuditVerificationResult;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.DenialReason;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计哈希链。
 *
 * <p>每条记录包含单调递增序号与 entryHash：
 * <pre>
 * entryHash_n = SHA-256(规范字段串 | entryHash_(n-1))
 * entryHash_0 的前驱为 64 个 0（GENESIS）
 * </pre>
 * 追加在同一把锁内完成“取尾哈希 → 分配序号 → 入链”，因此并发访问下
 * 记录既不会重复（序号唯一）也不会丢失（每次判定都同步追加，失败请求也追加）。
 * 任一条目的任何字段被改动，其 entryHash 立即与重新计算值不符，
 * 后续条目也会全部失配，{@link #verify()} 能定位到首个断裂序号。
 */
@Service
public class AuditChainService {

    static final String GENESIS_HASH = "0".repeat(64);

    private final Clock clock;
    private final CopyOnWriteArrayList<AuditEntry> chain = new CopyOnWriteArrayList<>();
    private final AtomicLong sequenceGen = new AtomicLong(0);

    public AuditChainService(Clock clock) {
        this.clock = clock;
    }

    /** 追加一条审计记录（允许/拒绝都会调用）。线程安全，序号严格递增。 */
    public AuditEntry append(String requestId, AccessRequest request,
                             boolean allowed, DenialReason denialReason) {
        synchronized (this) {
            long sequence = sequenceGen.incrementAndGet();
            String previousHash = chain.isEmpty()
                    ? GENESIS_HASH : chain.get(chain.size() - 1).entryHash();
            String timestamp = java.time.Instant.now(clock).toString();
            String entryHash = hash(canonical(
                    sequence, timestamp, requestId,
                    request.patientId(), request.consumerId(), request.purpose().name(),
                    allowed ? "ALLOW" : "DENY",
                    denialReason == null ? "" : denialReason.name(),
                    previousHash));
            AuditEntry entry = new AuditEntry(
                    sequence, timestamp, requestId,
                    request.patientId(), request.consumerId(), request.purpose().name(),
                    allowed ? "ALLOW" : "DENY",
                    denialReason == null ? null : denialReason.name(),
                    previousHash, entryHash);
            chain.add(entry);
            return entry;
        }
    }

    public List<AuditEntry> entries() {
        return List.copyOf(chain);
    }

    /** 校验序号连续、前后哈希衔接、每条哈希可重新计算复现。 */
    public AuditVerificationResult verify() {
        String previousHash = GENESIS_HASH;
        long expectedSequence = 1;
        for (AuditEntry entry : chain) {
            if (entry.sequence() != expectedSequence) {
                return new AuditVerificationResult(false, expectedSequence,
                        "sequence gap or duplicate at expected sequence " + expectedSequence);
            }
            if (!previousHash.equals(entry.previousHash())) {
                return new AuditVerificationResult(false, entry.sequence(),
                        "previousHash link broken at sequence " + entry.sequence());
            }
            String recomputed = hash(canonical(
                    entry.sequence(), entry.timestamp(), entry.requestId(),
                    entry.patientId(), entry.consumerId(), entry.purpose(),
                    entry.decision(), entry.denialReason() == null ? "" : entry.denialReason(),
                    entry.previousHash()));
            if (!recomputed.equals(entry.entryHash())) {
                return new AuditVerificationResult(false, entry.sequence(),
                        "entry hash mismatch at sequence " + entry.sequence()
                                + " (record tampered)");
            }
            previousHash = entry.entryHash();
            expectedSequence++;
        }
        return new AuditVerificationResult(true, null,
                "audit chain intact, " + chain.size() + " entries verified");
    }

    /** 仅供测试：篡改某条目的判定结论但保留原哈希，模拟审计记录被改动。 */
    void tamperDecisionForTesting(long sequence, String newDecision) {
        for (int i = 0; i < chain.size(); i++) {
            AuditEntry e = chain.get(i);
            if (e.sequence() == sequence) {
                chain.set(i, new AuditEntry(
                        e.sequence(), e.timestamp(), e.requestId(),
                        e.patientId(), e.consumerId(), e.purpose(),
                        newDecision, e.denialReason(), e.previousHash(), e.entryHash()));
                return;
            }
        }
        throw new IllegalArgumentException("sequence not found: " + sequence);
    }

    private static String canonical(long sequence, String timestamp, String requestId,
                                    String patientId, String consumerId, String purpose,
                                    String decision, String denialReason, String previousHash) {
        return String.join("|",
                Long.toString(sequence), nullSafe(timestamp), nullSafe(requestId),
                nullSafe(patientId), nullSafe(consumerId), nullSafe(purpose),
                nullSafe(decision), nullSafe(denialReason), nullSafe(previousHash));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
