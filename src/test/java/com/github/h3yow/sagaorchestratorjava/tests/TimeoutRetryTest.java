package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.core.*;
import com.github.h3yow.sagaorchestratorjava.participant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.COMPENSATE;
import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.EXECUTE;

/**
 * 场景2：超时与有界重试。
 * <ul>
 *   <li>参与方第1次调用超时（可重试的瞬时故障），第2次成功：编排最终 COMPLETED，无补偿；</li>
 *   <li>某参与方持续超时超过最大尝试次数：归类 RETRIES_EXHAUSTED 并逆序补偿，
 *       且能与 BUSINESS 业务失败区分开。</li>
 * </ul>
 */
@SpringBootTest
class TimeoutRetryTest {

    @Autowired SagaService sagaService;
    @Autowired InMemorySagaRepository repository;
    @Autowired SideEffectLedger ledger;
    @Autowired ResilientCaller caller;
    @Autowired InventoryParticipant inventory;
    @Autowired PaymentParticipant payment;
    @Autowired ShippingParticipant shipping;

    @Test
    void firstAttemptTimeoutSecondSucceeds_transientRetried() {
        String requestId = TestSupport.newRequestId("timeout-retry-ok");
        TestSupport.resetAll(repository, ledger, caller, inventory, payment, shipping);

        // 库存参与方第1次正向调用 sleep 30ms 后抛 TIMEOUT；第2次无故障 -> 重试成功
        inventory.setFaultPlan(FaultPlan.none()
                .timeoutAt(1, EXECUTE, "reserveStock", 30));

        SagaInstance instance = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);

        TestSupport.printReport("首次超时、重试成功", requestId, instance, ledger);

        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPLETED, instance.status());
        // 真正落地的正向副作用仍只有1次（失败的超时尝试未记账，无重复）
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "shipping", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "inventory", COMPENSATE);
    }

    @Test
    void persistentTimeout_retriesExhausted_thenCompensated() {
        String requestId = TestSupport.newRequestId("timeout-exhausted");
        TestSupport.resetAll(repository, ledger, caller, inventory, payment, shipping);

        // 支付参与方从第1次正向调用起持续超时（30ms），默认 maxAttempts=3 => 重试耗尽
        payment.setFaultPlan(FaultPlan.none()
                .failFrom(1, EXECUTE, FailureType.TIMEOUT, "支付网关持续超时"));

        SagaInstance instance = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);
        StepState paymentState = instance.stepState("charge-payment");

        TestSupport.printReport("支付持续超时、重试耗尽后逆序补偿", requestId, instance, ledger);

        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPENSATED, instance.status());
        // 失败原因可区分：重试耗尽（底层为 TIMEOUT），而不是业务失败
        org.junit.jupiter.api.Assertions.assertEquals(FailureType.RETRIES_EXHAUSTED,
                paymentState.failureType(),
                "持续超时的最终分类必须是 RETRIES_EXHAUSTED，实际=" + paymentState.failureType());
        // 库存已生效 -> 被逆序补偿恰好1次；支付未生效 -> 无正向记账、无补偿；发货未执行
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", COMPENSATE);
        TestSupport.assertEffectAbsent(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "shipping", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "shipping", COMPENSATE);
    }
}
