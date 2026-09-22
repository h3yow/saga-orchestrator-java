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

import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.COMPENSATE;
import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.EXECUTE;

/**
 * 场景1：幂等与重放。
 * <ul>
 *   <li>同一 requestId 顺序重复提交两次：副作用仍各只有一次，第二次返回同一终态；</li>
 *   <li>同一 requestId 被 N 个线程并发提交：副作用仍各只有一次，所有线程拿到同一终态；</li>
 *   <li>终态之后再次“执行”：直接返回，状态与流水不再增长。</li>
 * </ul>
 */
@SpringBootTest
class DuplicateSubmitTest {

    @Autowired SagaService sagaService;
    @Autowired InMemorySagaRepository repository;
    @Autowired SideEffectLedger ledger;
    @Autowired ResilientCaller caller;
    @Autowired InventoryParticipant inventory;
    @Autowired PaymentParticipant payment;
    @Autowired ShippingParticipant shipping;

    @Test
    void sequentialDuplicateSubmit_hasNoDuplicateSideEffects() {
        String requestId = TestSupport.newRequestId("dup-seq");
        TestSupport.resetAll(repository, ledger, caller, inventory, payment, shipping);

        SagaInstance first = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);
        SagaInstance second = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);

        TestSupport.printReport("顺序重复提交同一requestId", requestId, second, ledger);

        org.junit.jupiter.api.Assertions.assertSame(first, second, "重复提交必须返回同一实例");
        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPLETED, second.status());
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "shipping", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "inventory", COMPENSATE);
    }

    @Test
    void concurrentDuplicateSubmit_hasNoDuplicateSideEffects() throws Exception {
        String requestId = TestSupport.newRequestId("dup-concurrent");
        TestSupport.resetAll(repository, ledger, caller, inventory, payment, shipping);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SagaInstance>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);
            }));
        }
        start.countDown();

        SagaInstance first = futures.get(0).get();
        for (Future<SagaInstance> f : futures) {
            org.junit.jupiter.api.Assertions.assertSame(first, f.get(),
                    "并发提交必须复用同一个实例");
            org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPLETED, f.get().status());
        }
        pool.shutdown();

        TestSupport.printReport(threads + "线程并发提交同一requestId", requestId, first, ledger);

        // 判定依据：并发下每个参与方仍只有 1 次正向副作用、0 次补偿
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "shipping", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "payment", COMPENSATE);
        TestSupport.assertEffectAbsent(ledger, requestId, "shipping", COMPENSATE);
    }

    @Test
    void terminalSaga_cannotBeDrivenAgain() {
        String requestId = TestSupport.newRequestId("dup-terminal");
        TestSupport.resetAll(repository, ledger, caller, inventory, payment, shipping);

        SagaInstance instance = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);
        int historySizeAfterFirst = instance.history().size();

        // 已终结：再次提交/驱动不得再执行
        SagaInstance again = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);
        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPLETED, again.status());
        org.junit.jupiter.api.Assertions.assertEquals(historySizeAfterFirst,
                instance.history().size() - 1, // 第二次提交只追加一条去重流水，不追加执行流水
                "终态重放只允许出现一条去重事件，不允许新增执行事件");

        TestSupport.printReport("终态后再次提交（禁止再次执行）", requestId, instance, ledger);
        TestSupport.assertEffectOnce(ledger, requestId, "shipping", EXECUTE);
    }
}
