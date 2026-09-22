package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.saga.SagaInstance;
import com.github.h3yow.sagaorchestratorjava.saga.SagaStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景二：同一 requestId 重复提交（串行重放 + 并发到达），编排只执行一次，
 * 不产生重复副作用；已终结编排不会被再次执行；查询到的状态一致。
 */
class SagaIdempotentReplayTest extends AbstractSagaTest {

    @Test
    void duplicateSubmissionsExecuteOnlyOnce() {
        String requestId = "REQ-DUP-001";

        SagaInstance first = orderService.submitOrder(requestId);
        SagaInstance second = orderService.submitOrder(requestId);
        SagaInstance third = orderService.submitOrder(requestId);

        explain("重复提交(串行)", first);

        assertSame(first, second, "重复提交必须返回同一实例");
        assertSame(first, third);
        assertTerminalConsistent(first, SagaStatus.SUCCEEDED);
        // 每步只有 1 次正向尝试：终结后重放不会再次执行
        first.stepRecords().forEach(r ->
                assertEquals(1, r.forwardAttempts(), "step " + r.name() + " 不应被重复执行"));
        // 物理生效仍然只有三步各一次
        assertEquals(3, ledger.effectiveForwardCount());
        assertEquals(0, ledger.effectiveCompensateCount());

        // 重放（再次 submit）与按标识查询得到的是同一实例与最终状态
        SagaInstance replayed = orderService.submitOrder(requestId);
        assertSame(first, replayed, "按 requestId 重放得到同一实例与最终状态");
        assertEquals(SagaStatus.SUCCEEDED, replayed.status());
    }

    @Test
    void concurrentDuplicateSubmissionsExecuteOnlyOnce() throws Exception {
        String requestId = "REQ-DUP-CONC-001";
        int threads = 16;
        SagaInstance[] results = new SagaInstance[threads];

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                latch.countDown();
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                results[idx] = orderService.submitOrder(requestId);
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        SagaInstance s = results[0];
        explain("重复提交(16 并发)", s);

        for (SagaInstance r : results) {
            assertSame(s, r, "所有并发线程必须拿到同一实例");
        }
        assertTerminalConsistent(s, SagaStatus.SUCCEEDED);
        s.stepRecords().forEach(r ->
                assertEquals(1, r.forwardAttempts(), "并发下 step " + r.name() + " 只允许执行一次"));
        assertEquals(3, ledger.effectiveForwardCount(), "无重复副作用");
        assertEquals(0, ledger.effectiveCompensateCount());
    }
}
