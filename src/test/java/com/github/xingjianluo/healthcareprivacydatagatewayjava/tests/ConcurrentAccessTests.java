package com.github.xingjianluo.healthcareprivacydatagatewayjava.tests;

import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessPurpose;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessRequest;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AccessResult;
import com.github.xingjianluo.healthcareprivacydatagatewayjava.model.AuditEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发访问下审计链不重、不丢且校验完整。 */
class ConcurrentAccessTests {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentAccessTests.class);

    @Test
    @DisplayName("50 个线程各发 20 次混合判定：审计记录数量精确、序号唯一连续、哈希链有效")
    void concurrentRequestsProduceCompleteUniqueAuditChain() throws Exception {
        var ctx = GatewayTestSupport.newContext();
        int threads = 50;
        int perThread = 20;
        int total = threads * perThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<AccessResult> results = new ConcurrentLinkedQueue<>();

        for (int t = 0; t < threads; t++) {
            final int workerIndex = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // 混合场景：允许/撤回/越权/无授权，保证失败请求也参与并发留痕
                        AccessRequest request = switch ((workerIndex + i) % 4) {
                            case 0 -> new AccessRequest("P001", "C001", AccessPurpose.RESEARCH);
                            case 1 -> new AccessRequest("P001", "C001", AccessPurpose.TREATMENT);
                            case 2 -> new AccessRequest("P001", "C002", AccessPurpose.RESEARCH);
                            default -> new AccessRequest("P001", "C003", AccessPurpose.RESEARCH);
                        };
                        results.add(ctx.gateway().access(request));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发任务应在超时前全部完成");
        pool.shutdown();

        List<AuditEntry> entries = ctx.audit().entries();
        log.info("并发访问完成：发起请求={}, 收集结果={}, 审计记录={}",
                total, results.size(), entries.size());

        assertEquals(total, results.size(), "每次调用都必须返回结果");
        assertEquals(total, entries.size(), "每次访问（含失败）都必须留一条且仅一条审计");

        Set<Long> sequences = new HashSet<>();
        Set<String> requestIds = new HashSet<>();
        long expected = 1;
        for (AuditEntry entry : entries) {
            assertTrue(sequences.add(entry.sequence()),
                    "审计序号不得重复: " + entry.sequence());
            assertTrue(requestIds.add(entry.requestId()),
                    "requestId 不得重复: " + entry.requestId());
            assertEquals(expected, entry.sequence(), "审计序号必须连续无缺");
            expected++;
        }
        assertEquals(total, sequences.size());

        var verification = ctx.audit().verify();
        log.info("并发后审计链校验 valid={}, message={}", verification.valid(), verification.message());
        assertTrue(verification.valid(), verification.message());

        long denyCount = entries.stream().filter(e -> "DENY".equals(e.decision())).count();
        long allowCount = entries.size() - denyCount;
        log.info("审计判定统计 ALLOW={}, DENY={}", allowCount, denyCount);
        assertTrue(denyCount > 0, "混合场景中必须存在被拒绝的请求");
        assertTrue(allowCount > 0, "混合场景中必须存在被允许的请求");
    }
}
