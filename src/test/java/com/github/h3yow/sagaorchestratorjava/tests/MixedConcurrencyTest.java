package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.core.AccountOpeningSagaFactory;
import com.github.h3yow.sagaorchestratorjava.core.HistoryEvent;
import com.github.h3yow.sagaorchestratorjava.core.InMemorySagaRepository;
import com.github.h3yow.sagaorchestratorjava.core.OrderSagaFactory;
import com.github.h3yow.sagaorchestratorjava.core.ResilientCaller;
import com.github.h3yow.sagaorchestratorjava.core.SagaInstance;
import com.github.h3yow.sagaorchestratorjava.core.SagaService;
import com.github.h3yow.sagaorchestratorjava.core.SagaStatus;
import com.github.h3yow.sagaorchestratorjava.participant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.COMPENSATE;
import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.EXECUTE;

/**
 * 场景7：两种业务流程混合高并发提交（≥32 线程）。
 *
 * <p>负载构成：
 * <ul>
 *   <li>少量“热 key”：同一个 requestId 被多个线程同时提交（验证并发去重，只真正执行一次）；</li>
 *   <li>大量“冷 key”：不同 requestId 一起上（验证不同标识互不阻塞到接近串行）；</li>
 *   <li>两种 businessType 混合（下单三步 / 开户四步），参与方组合不重叠、互不影响。</li>
 * </ul>
 * 校验：同标识只执行一次、全部终态自洽、流水序号在每个实例内唯一连续、无丢失无重复事件；
 * 不同标识充分并行（总耗时远低于串行下界）。
 */
@SpringBootTest
class MixedConcurrencyTest {

    @Autowired SagaService sagaService;
    @Autowired InMemorySagaRepository repository;
    @Autowired SideEffectLedger ledger;
    @Autowired ResilientCaller caller;
    @Autowired InventoryParticipant inventory;
    @Autowired PaymentParticipant payment;
    @Autowired ShippingParticipant shipping;
    @Autowired KycParticipant kyc;
    @Autowired AccountParticipant account;
    @Autowired CardParticipant card;
    @Autowired NotificationParticipant notification;

    /** 提交任务：请求标识 + 业务类型。 */
    private record Task(String requestId, String businessType) {
    }

    @Test
    void thirtyTwoThreadsMixedFlow_duplicatesAndDistinct_runConcurrentlyWithConsistentState()
            throws Exception {
        AbstractSimulatedParticipant[] all = {
                inventory, payment, shipping, kyc, account, card, notification};
        TestSupport.resetAll(repository, ledger, caller, all);

        int threads = 32;
        // 8 个热 key（每个被 2 个线程同时提交）+ 16 个冷 key（每个 1 个线程）= 32 个提交任务
        int hotKeys = 8;
        int coldKeys = 16;

        List<Task> tasks = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < hotKeys; i++) {
            String id = TestSupport.newRequestId(i % 2 == 0 ? "mix-hot-order" : "mix-hot-acct");
            String type = i % 2 == 0
                    ? OrderSagaFactory.BUSINESS_TYPE
                    : AccountOpeningSagaFactory.BUSINESS_TYPE;
            tasks.add(new Task(id, type));
            tasks.add(new Task(id, type)); // 同一标识并发重复
        }
        for (int i = 0; i < coldKeys; i++) {
            String id = TestSupport.newRequestId(i % 2 == 0 ? "mix-cold-order" : "mix-cold-acct");
            String type = i % 2 == 0
                    ? OrderSagaFactory.BUSINESS_TYPE
                    : AccountOpeningSagaFactory.BUSINESS_TYPE;
            tasks.add(new Task(id, type));
        }
        org.junit.jupiter.api.Assertions.assertEquals(threads, tasks.size());

        // 并发期间不断查询，采集中间状态，事后校验“查询到的状态始终是合法/自洽的”
        List<String> observedStatuses = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicBoolean sampling =
                new java.util.concurrent.atomic.AtomicBoolean(true);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentHashMap<String, List<SagaInstance>> returnedByKey = new ConcurrentHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        long begin = System.currentTimeMillis();
        for (Task t : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                SagaInstance inst = sagaService.submit(t.requestId(), t.businessType());
                returnedByKey.computeIfAbsent(t.requestId(), k ->
                        Collections.synchronizedList(new ArrayList<>())).add(inst);
                return null;
            }));
        }

        // 状态采样线程：只要还有任务未完成，就遍历已知 key 查询并记录状态
        Thread sampler = new Thread(() -> {
            while (sampling.get()) {
                for (Task t : tasks) {
                    sagaService.query(t.requestId()).ifPresent(inst ->
                            observedStatuses.add(inst.status().name()));
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "mix-status-sampler");
        sampler.setDaemon(true);
        sampler.start();

        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        long elapsed = System.currentTimeMillis() - begin;
        sampling.set(false);
        sampler.interrupt();
        pool.shutdown();

        // ---------- 校验 1：所有不同标识都到达各自终态 ----------
        Set<String> distinctIds = new HashSet<>();
        for (Task t : tasks) {
            distinctIds.add(t.requestId());
        }
        org.junit.jupiter.api.Assertions.assertEquals(hotKeys + coldKeys, distinctIds.size());

        int orderCount = 0;
        int accountCount = 0;

        System.out.println();
        System.out.println("================================================================");
        System.out.println("[混合并发场景] 线程=" + threads + " 不同标识=" + distinctIds.size()
                + " 热key(重复提交)=" + hotKeys + " 冷key=" + coldKeys
                + " 总耗时=" + elapsed + "ms");

        for (String id : distinctIds) {
            SagaInstance instance = sagaService.query(id).orElseThrow();
            SagaStatus status = instance.status();
            org.junit.jupiter.api.Assertions.assertTrue(status == SagaStatus.COMPLETED
                            || status == SagaStatus.COMPENSATED
                            || status == SagaStatus.COMPENSATION_FAILED,
                    "终态必须自洽，requestId=" + id + " 实际=" + status);

            // ---------- 校验 2：同一 requestId 的多个并发提交返回同一实例 ----------
            List<SagaInstance> returned = returnedByKey.get(id);
            org.junit.jupiter.api.Assertions.assertNotNull(returned, "缺少提交返回: " + id);
            for (SagaInstance r : returned) {
                org.junit.jupiter.api.Assertions.assertSame(instance, r,
                        "同一 requestId 并发提交必须复用同一实例: " + id);
            }

            boolean order = instance.definition().type()
                    .equalsIgnoreCase(OrderSagaFactory.BUSINESS_TYPE);
            if (order) {
                orderCount++;
            } else {
                accountCount++;
                org.junit.jupiter.api.Assertions.assertEquals(4, instance.stepCount(),
                        "开户流程必须四步: " + id);
            }

            // ---------- 校验 3：流水序号在实例内唯一、连续、可追溯（1..N 不丢不重不乱） ----------
            List<HistoryEvent> history = instance.history();
            long expectedSeq = 1;
            Set<Long> seqs = new HashSet<>();
            for (HistoryEvent e : history) {
                org.junit.jupiter.api.Assertions.assertEquals(expectedSeq, e.seq(),
                        "流水序号必须从1连续递增，requestId=" + id);
                org.junit.jupiter.api.Assertions.assertTrue(seqs.add(e.seq()),
                        "流水序号不允许重复，requestId=" + id);
                org.junit.jupiter.api.Assertions.assertEquals(id, e.requestId(),
                        "流水必须可追溯到本请求，requestId=" + id);
                expectedSeq++;
            }

            // ---------- 校验 4：终态副作用与流程严格一致 ----------
            if (order && status == SagaStatus.COMPLETED) {
                TestSupport.assertEffectOnce(ledger, id, "inventory", EXECUTE);
                TestSupport.assertEffectOnce(ledger, id, "payment", EXECUTE);
                TestSupport.assertEffectOnce(ledger, id, "shipping", EXECUTE);
            }
            if (!order && status == SagaStatus.COMPLETED) {
                TestSupport.assertEffectOnce(ledger, id, "kyc", EXECUTE);
                TestSupport.assertEffectOnce(ledger, id, "account", EXECUTE);
                TestSupport.assertEffectOnce(ledger, id, "card", EXECUTE);
                TestSupport.assertEffectOnce(ledger, id, "notification", EXECUTE);
            }
        }

        // ---------- 校验 5：并发期间采样到的中间状态全部是合法生命周期状态 ----------
        Set<String> legal = Set.of("PENDING", "RUNNING", "COMPENSATING",
                "COMPLETED", "COMPENSATED", "COMPENSATION_FAILED");
        for (String s : observedStatuses) {
            org.junit.jupiter.api.Assertions.assertTrue(legal.contains(s),
                    "并发期间查询到非法状态: " + s);
        }

        // ---------- 校验 6：不同标识不能被拖成接近串行 ----------
        // 无故障下每个实例仅毫秒级；24 个不同标识若近串行会明显更久。给宽松上界 15s，
        // 主要防回归（派发池/同步粒度过大导致的全局串行）。
        org.junit.jupiter.api.Assertions.assertTrue(elapsed < 15_000,
                "不同标识应并行推进，实际总耗时 " + elapsed + "ms 接近串行");

        System.out.println("[终态分布] 下单流程=" + orderCount + " 开户流程=" + accountCount
                + "；全部到达终态；并发期间采样状态" + observedStatuses.size() + "次均合法。");

        // 代表性流水（热 key 之一），打印请求标识+流水作为判定依据
        String sampleHotId = returnedByKey.keySet().stream()
                .filter(id -> id.startsWith("mix-hot-acct")).findFirst().orElseThrow();
        SagaInstance sample = sagaService.query(sampleHotId).orElseThrow();
        TestSupport.printReport("混合并发代表（开户热key，被2线程并发提交）", sampleHotId, sample, ledger);

        // 热 key 被提交两次，但四步正向仍各只有一次
        for (String p : new String[]{"kyc", "account", "card", "notification"}) {
            TestSupport.assertEffectOnce(ledger, sampleHotId, p, EXECUTE);
            TestSupport.assertEffectAbsent(ledger, sampleHotId, p, COMPENSATE);
        }
        System.out.println("[并发判定汇总] 32线程混合提交：同标识" + hotKeys
                + "组重复均只执行一次；不同标识" + distinctIds.size() + "个并行完成，总耗时"
                + elapsed + "ms；流水序号连续唯一、状态采样全部合法。");
        System.out.println("================================================================");
    }
}
