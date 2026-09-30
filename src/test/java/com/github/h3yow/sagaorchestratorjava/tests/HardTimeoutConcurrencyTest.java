package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.core.*;
import com.github.h3yow.sagaorchestratorjava.participant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.EXECUTE;

/**
 * 场景8：硬超时场景下的并发与容量。
 *
 * <p>32 个线程同时提交，其中：
 * <ul>
 *   <li>一部分请求命中“下游长期不返回”（会占住派发工作线程直到 120ms 硬超时掐断，
 *       最终重试耗尽并回滚）；</li>
 *   <li>其余请求无故障（必须迅速完成）。</li>
 * </ul>
 * 由于每个卡住调用只占用自己的派发线程，正常请求绝不能被拖到与硬超时同量级；
 * 同时校验：所有被掐断请求的终态为 COMPENSATED、失败底层分类为 HARD_TIMEOUT、无残留副作用；
 * 所有正常请求终态 COMPLETED。
 *
 * 配置：硬超时 120ms / 尝试 2 次 / 退避 10ms / 派发池 64（≥32，保证并发不排队成串行）。
 */
@SpringBootTest(properties = {
        "saga.resilience.call-timeout-millis=120",
        "saga.resilience.max-attempts=2",
        "saga.resilience.backoff-millis=10",
        "saga.resilience.dispatch-pool-size=64"
})
class HardTimeoutConcurrencyTest {

    @Autowired SagaService sagaService;
    @Autowired InMemorySagaRepository repository;
    @Autowired SideEffectLedger ledger;
    @Autowired ResilientCaller caller;
    @Autowired InventoryParticipant inventory;
    @Autowired PaymentParticipant payment;
    @Autowired ShippingParticipant shipping;

    private record Job(String requestId, boolean hanging) {
    }

    @Test
    void hangingCallsDoNotBlockHealthyConcurrency_andStatesStayConsistent() throws Exception {
        TestSupport.resetAll(repository, ledger, caller, inventory, payment, shipping);

        int threads = 32;
        int hanging = 10;   // 10 个请求的库存步骤长期卡死 -> 硬超时耗尽回滚
        int healthy = 22;   // 22 个请求无故障 -> 全部成功

        List<Job> jobs = new ArrayList<>();
        FaultPlan plan = FaultPlan.none();
        for (int i = 0; i < hanging; i++) {
            String id = TestSupport.newRequestId("cap-hang-" + i);
            jobs.add(new Job(id, true));
            // 仅该请求：库存正向调用（maxAttempts=2，共2次尝试）长期挂起，等待编排器掐断
            plan.hangFromForRequest(id, 1, EXECUTE, "reserveStock", 10_000L);
        }
        for (int i = 0; i < healthy; i++) {
            jobs.add(new Job(TestSupport.newRequestId("cap-ok-" + i), false));
        }
        inventory.setFaultPlan(plan);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        for (Job job : jobs) {
            futures.add(pool.submit(() -> {
                start.await();
                long begin = System.currentTimeMillis();
                sagaService.submit(job.requestId(), OrderSagaFactory.BUSINESS_TYPE);
                return System.currentTimeMillis() - begin;
            }));
        }
        start.countDown();

        List<Long> latencies = new ArrayList<>();
        for (Future<Long> f : futures) {
            latencies.add(f.get(60, TimeUnit.SECONDS));
        }
        long totalElapsed = System.currentTimeMillis() - t0;
        pool.shutdown();

        // 恢复故障计划，避免干扰（仅诊断意义；测试间 resetAll 也会清）
        inventory.setFaultPlan(FaultPlan.none());

        int compensated = 0;
        int completed = 0;
        for (Job job : jobs) {
            SagaInstance inst = sagaService.query(job.requestId()).orElseThrow();
            if (job.hanging()) {
                org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPENSATED, inst.status(),
                        "卡死请求应硬超时耗尽后回滚: " + job.requestId());
                StepState failedStep = inst.stepState("reserve-stock");
                org.junit.jupiter.api.Assertions.assertEquals(FailureType.RETRIES_EXHAUSTED,
                        failedStep.failureType());
                org.junit.jupiter.api.Assertions.assertEquals(FailureType.HARD_TIMEOUT,
                        failedStep.rootCauseType(),
                        "被编排侧掐断的失败底层分类必须 HARD_TIMEOUT: " + job.requestId());
                TestSupport.assertEffectAbsent(ledger, job.requestId(), "inventory", EXECUTE);
                TestSupport.assertEffectAbsent(ledger, job.requestId(), "payment", EXECUTE);
                TestSupport.assertEffectAbsent(ledger, job.requestId(), "shipping", EXECUTE);
                compensated++;
            } else {
                org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPLETED, inst.status(),
                        "正常请求不应被卡死下游阻塞: " + job.requestId());
                TestSupport.assertEffectOnce(ledger, job.requestId(), "inventory", EXECUTE);
                TestSupport.assertEffectOnce(ledger, job.requestId(), "payment", EXECUTE);
                TestSupport.assertEffectOnce(ledger, job.requestId(), "shipping", EXECUTE);
                completed++;
            }

            // 流水序号连续唯一
            List<HistoryEvent> history = inst.history();
            long seq = 1;
            for (HistoryEvent e : history) {
                org.junit.jupiter.api.Assertions.assertEquals(seq, e.seq());
                seq++;
            }
        }

        long healthyMaxLatency = Long.MIN_VALUE;
        for (int i = hanging; i < jobs.size(); i++) {
            healthyMaxLatency = Math.max(healthyMaxLatency, latencies.get(i));
        }

        System.out.println();
        System.out.println("================================================================");
        System.out.println("[硬超时容量场景] 并发=" + threads + " 卡死请求=" + compensated
                + "（各2次尝试均在120ms处被掐断） 正常请求=" + completed);
        System.out.println("[判定依据] 总耗时=" + totalElapsed + "ms；正常请求单请求最大耗时="
                + healthyMaxLatency + "ms（硬超时单次上限120ms）");
        System.out.println("被掐断请求全部 COMPENSATED 且 HARD_TIMEOUT 零副作用；正常请求全部 COMPLETED。");

        // 核心容量断言：正常请求不能被卡死调用拖到接近一次硬超时的量级
        org.junit.jupiter.api.Assertions.assertTrue(healthyMaxLatency < 120,
                "正常请求必须与卡死下游并行，单请求耗时应小于硬超时上限，实际="
                        + healthyMaxLatency + "ms（派发线程疑似不足导致排队串行）");
        // 全部 32 请求的总耗时也应接近“一批硬超时 + 退避”，而不是 10 批串行（<2s 宽松上界）
        org.junit.jupiter.api.Assertions.assertTrue(totalElapsed < 2000,
                "总耗时异常，疑似不同请求被拖成串行: " + totalElapsed + "ms");

        // 代表流水留痕（取一个卡死请求）
        String sampleHangId = jobs.get(0).requestId();
        TestSupport.printReport("硬超时容量代表（库存卡死、掐断耗尽回滚）",
                sampleHangId, sagaService.query(sampleHangId).orElseThrow(), ledger);
        System.out.println("================================================================");
    }
}
