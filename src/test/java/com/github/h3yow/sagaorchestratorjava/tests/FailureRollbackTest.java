package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.core.*;
import com.github.h3yow.sagaorchestratorjava.participant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.COMPENSATE;
import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.EXECUTE;

/**
 * 场景3：参与方持续失败触发逆序回滚。
 * <ul>
 *   <li>支付参与方返回不可重试的 BUSINESS 失败：不重试、立即逆序补偿库存，
 *       失败原因归类为 BUSINESS，与超时/重试耗尽可区分；</li>
 *   <li>补偿动作本身被重放（终态后重复提交）：补偿幂等，账本中补偿仍恰好1次；</li>
 *   <li>第二步失败时第一步补偿，第三步从未执行；不存在部分生效残留。</li>
 * </ul>
 */
@SpringBootTest
class FailureRollbackTest {

    @Autowired SagaService sagaService;
    @Autowired InMemorySagaRepository repository;
    @Autowired SideEffectLedger ledger;
    @Autowired ResilientCaller caller;
    @Autowired InventoryParticipant inventory;
    @Autowired PaymentParticipant payment;
    @Autowired ShippingParticipant shipping;

    @Test
    void businessRejection_rollsBackImmediately_withoutRetry() {
        String requestId = TestSupport.newRequestId("biz-fail");
        TestSupport.resetAll(repository, ledger, caller, inventory, payment, shipping);

        payment.setFaultPlan(FaultPlan.none()
                .failFrom(1, EXECUTE, FailureType.BUSINESS, "余额不足，业务拒绝扣款"));

        long start = System.currentTimeMillis();
        SagaInstance instance = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);
        long elapsed = System.currentTimeMillis() - start;
        StepState paymentState = instance.stepState("charge-payment");

        TestSupport.printReport("支付业务失败(不可重试)，立即回滚", requestId, instance, ledger);

        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPENSATED, instance.status());
        org.junit.jupiter.api.Assertions.assertEquals(FailureType.BUSINESS,
                paymentState.failureType(),
                "业务失败必须归类 BUSINESS 且不被重试包装，实际=" + paymentState.failureType());
        // 不可重试 => 不应出现退避等待（backoff=50ms，重试会显著更久）
        org.junit.jupiter.api.Assertions.assertTrue(elapsed < 40,
                "业务失败必须立即失败不重试，实际耗时" + elapsed + "ms");

        // 无部分生效：库存 正向1次+补偿1次；支付 0；发货 0
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", COMPENSATE);
        TestSupport.assertEffectAbsent(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "shipping", EXECUTE);
    }

    @Test
    void compensationIsIdempotent_whenRequestReplayedAfterRollback() {
        String requestId = TestSupport.newRequestId("comp-idem");
        TestSupport.resetAll(repository, ledger, caller, inventory, payment, shipping);

        // 发货参与方持续瞬时失败 => 库存、支付都已生效后回滚两步补偿
        shipping.setFaultPlan(FaultPlan.none()
                .failFrom(1, EXECUTE, FailureType.TRANSIENT, "物流系统持续不可用"));

        SagaInstance first = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);
        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPENSATED, first.status());

        // 回滚后客户端超时重试、重复提交同一 requestId
        SagaInstance replayed = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);

        TestSupport.printReport("回滚后重复提交：补偿必须幂等", requestId, replayed, ledger);

        org.junit.jupiter.api.Assertions.assertSame(first, replayed);
        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPENSATED, replayed.status());

        // 正向：库存/支付各1次，发货0次；补偿：库存/支付各恰好1次（重放不产生重复补偿）
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "shipping", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", COMPENSATE);
        TestSupport.assertEffectOnce(ledger, requestId, "payment", COMPENSATE);
        TestSupport.assertEffectAbsent(ledger, requestId, "shipping", COMPENSATE);
    }
}
