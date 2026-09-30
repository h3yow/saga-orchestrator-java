package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.core.*;
import com.github.h3yow.sagaorchestratorjava.participant.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.COMPENSATE;
import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.EXECUTE;

/**
 * 编排侧硬超时场景（区别于参与方自报超时 {@link FailureType#TIMEOUT}）：
 * 下游“卡住”——长时间既不返回也不报错。编排器按每次调用时间上限
 * （saga.resilience.call-timeout-millis=150）主动停止等待、中断调用，
 * 归类为 {@link FailureType#ORCHESTRATOR_TIMEOUT}（可重试瞬时故障），
 * 再走正常重试上限与失败回滚。
 *
 * <ul>
 *   <li>下游永久挂起：每次尝试都在约 150ms 被掐断，3 次耗尽 -> RETRIES_EXHAUSTED -> 逆序补偿；
 *       被掐断调用不算成功、不记账，无任何已生效痕迹；</li>
 *   <li>首次被掐断、第二次恢复正常：重试成功，编排 COMPLETED；</li>
 *   <li>两类超时可区分：参与方自报超时仍归类 TIMEOUT（由既有 TimeoutRetryTest 覆盖），
 *       硬超时归类 ORCHESTRATOR_TIMEOUT。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "saga.resilience.max-attempts=3",
        "saga.resilience.backoff-millis=20",
        "saga.resilience.call-timeout-millis=150"
})
class HardTimeoutTest {

    @Autowired SagaService sagaService;
    @Autowired InMemorySagaRepository repository;
    @Autowired SideEffectLedger ledger;
    @Autowired ResilientCaller caller;
    @Autowired InventoryParticipant inventory;
    @Autowired PaymentParticipant payment;
    @Autowired ShippingParticipant shipping;
    @Autowired FlightParticipant flight;
    @Autowired HotelParticipant hotel;
    @Autowired VisaParticipant visa;
    @Autowired LoyaltyParticipant loyalty;

    private void resetAll() {
        TestSupport.resetAll(repository, ledger, caller,
                inventory, payment, shipping, flight, hotel, visa, loyalty);
    }

    @Test
    void downstreamHangsForever_orchestratorCutsOff_retriesExhausted_thenCompensated() {
        String requestId = TestSupport.newRequestId("hard-timeout-exhausted");
        resetAll();

        // payment 永久挂起：3 次尝试都应在约 150ms 被编排器掐断
        payment.setFaultPlan(FaultPlan.none().hangFrom(1, EXECUTE, "charge"));

        long start = System.currentTimeMillis();
        SagaInstance instance = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);
        long elapsed = System.currentTimeMillis() - start;
        StepState paymentState = instance.stepState("charge-payment");

        TestSupport.printReport("下游永久挂起、编排侧硬超时掐断并回滚", requestId, instance, ledger);

        Assertions.assertEquals(SagaStatus.COMPENSATED, instance.status());
        Assertions.assertEquals(FailureType.RETRIES_EXHAUSTED, paymentState.failureType());
        // 判定依据中必须保留“末次分类=ORCHESTRATOR_TIMEOUT”，与参与方自报 TIMEOUT 区分
        Assertions.assertTrue(
                paymentState.failureMessage().contains("ORCHESTRATOR_TIMEOUT"),
                "硬超时耗尽的判定依据必须含 ORCHESTRATOR_TIMEOUT，实际=" + paymentState.failureMessage());
        // 流水中必须能看到编排侧硬超时的判定
        List<HistoryEvent> failedEvent = instance.history().stream()
                .filter(e -> "STEP_FAILED".equals(e.eventType()) && "charge-payment".equals(e.stepName()))
                .toList();
        Assertions.assertEquals(1, failedEvent.size());
        Assertions.assertTrue(failedEvent.get(0).detail().contains("ORCHESTRATOR_TIMEOUT"));

        // 耗时下界：3 次 * 150ms + 2 次退避 = 约 490ms（证明确实等到上限才掐，而非立即失败）
        Assertions.assertTrue(elapsed >= 400,
                "硬超时必须等满时间上限再掐断，实际耗时" + elapsed + "ms");
        // 上界：远小于“放任挂起”（若不掐断会无限等待）；给足调度余量
        Assertions.assertTrue(elapsed < 3000,
                "硬超时必须及时返回，实际耗时" + elapsed + "ms");

        // 被掐断的 payment 调用绝不能记账；inventory 已生效后被补偿恰好1次；shipping 未执行
        TestSupport.assertEffectAbsent(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", COMPENSATE);
        TestSupport.assertEffectAbsent(ledger, requestId, "shipping", EXECUTE);
    }

    @Test
    void firstCallHungAndCutOff_secondCallRecovers_sagaCompletes() {
        String requestId = TestSupport.newRequestId("hard-timeout-recover");
        resetAll();

        // inventory 仅第 1 次正向调用挂起；第 2 次无故障正常返回
        inventory.setFaultPlan(FaultPlan.none().hangAt(1, EXECUTE, "reserveStock"));

        SagaInstance instance = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);

        TestSupport.printReport("首次调用挂起被掐断、重试恢复成功", requestId, instance, ledger);

        Assertions.assertEquals(SagaStatus.COMPLETED, instance.status());
        // inventory 最终只落地 1 次副作用（被掐断的首次未记账），其余步骤各 1 次，无补偿
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "shipping", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "inventory", COMPENSATE);
    }
}
