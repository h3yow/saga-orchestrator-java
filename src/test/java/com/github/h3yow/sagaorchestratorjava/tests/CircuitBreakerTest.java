package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.core.*;
import com.github.h3yow.sagaorchestratorjava.participant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.EXECUTE;

/**
 * 场景4：熔断。
 * 配置：failure-threshold=3、max-attempts=1（让每次请求恰好产生1次失败调用，便于计数）、
 * open-millis=300。
 *
 * <ol>
 *   <li>库存参与方持续 TRANSIENT 失败：前 3 个请求分别失败并补偿；第 3 次失败后熔断 OPEN；</li>
 *   <li>OPEN 期间的第 4 个请求在库存步骤被快速失败，分类 CIRCUIT_OPEN（判定依据：未发起下游调用），
 *       耗时远小于一次真实调用；</li>
 *   <li>故障恢复（移除故障计划）并等待 OPEN 超时后，新请求经 HALF_OPEN 试探成功，
 *       熔断回到 CLOSED，编排恢复 COMPLETED。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "saga.resilience.max-attempts=1",
        "saga.resilience.circuit-failure-threshold=3",
        "saga.resilience.circuit-open-millis=300",
        "saga.resilience.circuit-success-threshold=1"
})
class CircuitBreakerTest {

    @Autowired SagaService sagaService;
    @Autowired InMemorySagaRepository repository;
    @Autowired SideEffectLedger ledger;
    @Autowired ResilientCaller caller;
    @Autowired InventoryParticipant inventory;
    @Autowired PaymentParticipant payment;
    @Autowired ShippingParticipant shipping;

    @Test
    void opensAfterThreshold_fastFails_thenAutoRecovers() throws Exception {
        TestSupport.resetAll(repository, ledger, caller, inventory, payment, shipping);
        inventory.setFaultPlan(FaultPlan.none()
                .failFrom(1, EXECUTE, FailureType.TRANSIENT, "库存系统持续不可用"));

        // 前 3 个请求：每个在库存处真实失败1次并回滚（无后续步骤生效）
        String r1 = TestSupport.newRequestId("cb-1");
        String r2 = TestSupport.newRequestId("cb-2");
        String r3 = TestSupport.newRequestId("cb-3");
        sagaService.submit(r1, OrderSagaFactory.BUSINESS_TYPE);
        sagaService.submit(r2, OrderSagaFactory.BUSINESS_TYPE);
        SagaInstance third = sagaService.submit(r3, OrderSagaFactory.BUSINESS_TYPE);

        org.junit.jupiter.api.Assertions.assertEquals(CircuitBreaker.State.OPEN,
                caller.breakerState("inventory"),
                "连续3次真实失败后熔断必须 OPEN，实际=" + caller.breakerState("inventory"));

        // 第 4 个请求：OPEN 期间快速失败，不发起调用，分类必须为 CIRCUIT_OPEN
        String r4 = TestSupport.newRequestId("cb-open-fastfail");
        long start = System.currentTimeMillis();
        SagaInstance blocked = sagaService.submit(r4, OrderSagaFactory.BUSINESS_TYPE);
        long elapsed = System.currentTimeMillis() - start;
        StepState blockedStep = blocked.stepState("reserve-stock");

        TestSupport.printReport("熔断开启期间快速失败", r4, blocked, ledger);

        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPENSATED, blocked.status());
        org.junit.jupiter.api.Assertions.assertEquals(FailureType.CIRCUIT_OPEN,
                blockedStep.failureType(),
                "熔断开启时失败分类必须是 CIRCUIT_OPEN，实际=" + blockedStep.failureType());
        org.junit.jupiter.api.Assertions.assertTrue(elapsed < 30,
                "熔断快速失败不应发起下游调用，实际耗时" + elapsed + "ms");
        TestSupport.assertEffectAbsent(ledger, r4, "inventory", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, r4, "payment", EXECUTE);

        // 故障恢复：等待 OPEN 窗口过后，HALF_OPEN 放一次试探，成功则自动 CLOSED
        inventory.setFaultPlan(FaultPlan.none());
        Thread.sleep(450);

        String r5 = TestSupport.newRequestId("cb-recovered");
        SagaInstance recovered = sagaService.submit(r5, OrderSagaFactory.BUSINESS_TYPE);

        TestSupport.printReport("故障恢复后试探成功、熔断自动关闭", r5, recovered, ledger);

        org.junit.jupiter.api.Assertions.assertEquals(CircuitBreaker.State.CLOSED,
                caller.breakerState("inventory"),
                "HALF_OPEN 试探成功后熔断必须恢复 CLOSED，实际=" + caller.breakerState("inventory"));
        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPLETED, recovered.status());
        TestSupport.assertEffectOnce(ledger, r5, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, r5, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, r5, "shipping", EXECUTE);

        System.out.println("[熔断判定汇总] r1~r3 真实失败各1次触发阈值3 -> OPEN；"
                + "r4=" + blockedStep.failureType()
                + "(耗时" + elapsed + "ms，未触达下游)；r5 HALF_OPEN 试探成功 -> CLOSED，编排恢复。");
    }
}
