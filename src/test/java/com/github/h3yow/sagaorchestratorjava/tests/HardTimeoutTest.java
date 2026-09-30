package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.core.*;
import com.github.h3yow.sagaorchestratorjava.participant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.COMPENSATE;
import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.EXECUTE;

/**
 * 场景5：编排侧硬超时（下游长期不返回，由编排器达到时间上限主动掐断）。
 *
 * <p>与既有“参与方自报超时”的区别：
 * <ul>
 *   <li>{@link FailureType#HARD_TIMEOUT}：下游只挂起、不上报错误，编排器在 call-timeout-millis
 *       达到后 future.cancel 主动掐断等待，按可重试瞬时故障处理；</li>
 *   <li>{@link FailureType#TIMEOUT}：参与方自己睡眠后判定并上报，照旧可用，两者结果可区分。</li>
 * </ul>
 *
 * 配置：每次下游调用时间上限 120ms；退避 20ms；尝试 3 次。
 */
@SpringBootTest(properties = {
        "saga.resilience.call-timeout-millis=120",
        "saga.resilience.max-attempts=3",
        "saga.resilience.backoff-millis=20"
})
class HardTimeoutTest {

    @Autowired SagaService sagaService;
    @Autowired InMemorySagaRepository repository;
    @Autowired SideEffectLedger ledger;
    @Autowired ResilientCaller caller;
    @Autowired HardTimeoutCaller hardTimeoutCaller;
    @Autowired InventoryParticipant inventory;
    @Autowired PaymentParticipant payment;
    @Autowired ShippingParticipant shipping;

    /**
     * 下游首次长期不返回 -> 编排器 120ms 掐断（HARD_TIMEOUT，可重试）；
     * 第二次无故障成功 -> 编排 COMPLETED，无任何副作用残留/重复。
     */
    @Test
    void hardTimeoutFirstAttempt_retriedThenSucceeds() {
        String requestId = TestSupport.newRequestId("hard-timeout-recover");
        TestSupport.resetAll(repository, ledger, caller, inventory, payment, shipping);

        // 库存第 1 次正向调用长期挂起（10s，远大于 120ms 上限），第 2 次起无故障
        inventory.setFaultPlan(FaultPlan.none()
                .hangAt(1, EXECUTE, "reserveStock", 10_000L));

        long start = System.currentTimeMillis();
        SagaInstance instance = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);
        long elapsed = System.currentTimeMillis() - start;

        TestSupport.printReport("下游卡住被编排侧硬超时掐断，重试后成功", requestId, instance, ledger);

        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPLETED, instance.status());
        // 不能等满 10s：约 120ms 即被掐断，再加上退避与后续步骤，整体远小于挂起时长
        org.junit.jupiter.api.Assertions.assertTrue(elapsed < 2000,
                "编排器必须在硬超时上限处掐断等待，实际耗时" + elapsed + "ms（疑似等满下游挂起）");
        // 被掐断的第一次调用不记账；最终成功的副作用恰好 1 次
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "shipping", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "inventory", COMPENSATE);
    }

    /**
     * 下游持续不返回 -> 每次尝试都被编排器掐断，重试耗尽后走失败回滚；
     * 最终分类 RETRIES_EXHAUSTED，底层分类必须是 HARD_TIMEOUT（与参与方自报 TIMEOUT 区分）；
     * 被掐断调用一律无正向副作用、无残留，前序已生效步骤被逆序补偿恰好 1 次。
     */
    @Test
    void persistentHang_hardTimeoutExhausted_thenCompensated() {
        String requestId = TestSupport.newRequestId("hard-timeout-exhausted");
        TestSupport.resetAll(repository, ledger, caller, inventory, payment, shipping);

        // 支付从第 1 次正向调用起持续挂起，3 次尝试都在 120ms 处被掐断
        payment.setFaultPlan(FaultPlan.none()
                .hangFrom(1, EXECUTE, "charge", 10_000L));

        long start = System.currentTimeMillis();
        SagaInstance instance = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);
        long elapsed = System.currentTimeMillis() - start;
        StepState paymentState = instance.stepState("charge-payment");

        TestSupport.printReport("下游持续卡死，硬超时重试耗尽后逆序补偿", requestId, instance, ledger);

        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPENSATED, instance.status());
        org.junit.jupiter.api.Assertions.assertEquals(FailureType.RETRIES_EXHAUSTED,
                paymentState.failureType(), "最终分类必须是 RETRIES_EXHAUSTED");
        org.junit.jupiter.api.Assertions.assertEquals(FailureType.HARD_TIMEOUT,
                paymentState.rootCauseType(),
                "底层分类必须是编排侧硬超时 HARD_TIMEOUT，实际=" + paymentState.rootCauseType());
        // 3 次尝试 * 120ms + 2 次退避 * 20ms ≈ 400ms；远小于等满挂起的 30s
        org.junit.jupiter.api.Assertions.assertTrue(elapsed < 3000,
                "三次尝试都应在硬超时处被掐断，实际耗时" + elapsed + "ms");
        // 库存已生效 -> 逆序补偿恰好1次；支付三次被掐断 -> 无任何正向记账；发货未执行
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", COMPENSATE);
        TestSupport.assertEffectAbsent(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "payment", COMPENSATE);
        TestSupport.assertEffectAbsent(ledger, requestId, "shipping", EXECUTE);

        System.out.println("[硬超时判定汇总] requestId=" + requestId
                + " 每次调用时间上限=" + hardTimeoutCaller.callTimeoutMillis()
                + "ms；3 次尝试均为 HARD_TIMEOUT(编排侧主动掐断)，最终 RETRIES_EXHAUSTED，"
                + "总耗时" + elapsed + "ms；库存补偿1次，支付0副作用，无残留。");
    }

    /**
     * 对照用例：参与方自报超时（TIMEOUT）路径在开启编排侧硬超时后依旧可用，
     * 其底层分类必须仍是 TIMEOUT，而不是 HARD_TIMEOUT。
     */
    @Test
    void participantReportedTimeout_stillClassifiedAsTimeout() {
        String requestId = TestSupport.newRequestId("self-timeout-compat");
        TestSupport.resetAll(repository, ledger, caller, inventory, payment, shipping);

        // 支付睡眠 30ms（< 120ms 上限）后自行抛 TIMEOUT，持续3次 -> 耗尽回滚
        payment.setFaultPlan(FaultPlan.none()
                .timeoutAt(1, EXECUTE, "charge", 30)
                .timeoutAt(2, EXECUTE, "charge", 30)
                .timeoutAt(3, EXECUTE, "charge", 30));

        SagaInstance instance = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);
        StepState paymentState = instance.stepState("charge-payment");

        TestSupport.printReport("参与方自报超时（兼容路径），与硬超时区分", requestId, instance, ledger);

        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPENSATED, instance.status());
        org.junit.jupiter.api.Assertions.assertEquals(FailureType.RETRIES_EXHAUSTED,
                paymentState.failureType());
        org.junit.jupiter.api.Assertions.assertEquals(FailureType.TIMEOUT,
                paymentState.rootCauseType(),
                "参与方自报超时底层分类必须保持 TIMEOUT，实际=" + paymentState.rootCauseType());
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", COMPENSATE);
        TestSupport.assertEffectAbsent(ledger, requestId, "payment", EXECUTE);
    }
}
