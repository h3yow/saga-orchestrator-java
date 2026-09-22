package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.demo.OrderSagaParticipants;
import com.github.h3yow.sagaorchestratorjava.mock.MockScenario;
import com.github.h3yow.sagaorchestratorjava.resilience.CircuitBreaker;
import com.github.h3yow.sagaorchestratorjava.resilience.ResilienceCaller;
import com.github.h3yow.sagaorchestratorjava.saga.SagaInstance;
import com.github.h3yow.sagaorchestratorjava.saga.SagaStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景七：
 * 7a 瞬时故障在重试预算内恢复 -> 整体 SUCCEEDED，物理调用多次但只生效一次；
 * 7b 熔断与编排联动：持续失败使熔断开启，之后的新编排被快速失败并回滚，零物理调用。
 */
@TestPropertySource(properties = "saga.resilience.failure-threshold=3")
class SagaRecoveryAndIntegrationTest extends AbstractSagaTest {

    @Autowired
    private ResilienceCaller resilienceCaller;

    @Test
    void flakyParticipantRecoversWithinRetryBudget() {
        String requestId = "REQ-FLAKY-001";
        // 前 2 次物理调用瞬时失败，第 3 次恢复成功（maxAttempts=3 内，未达熔断阈值 3）
        orderService.configureParticipant(OrderSagaParticipants.PAYMENT, MockScenario.flaky(2));

        SagaInstance s = orderService.submitOrder(requestId);
        explain("瞬时故障后恢复", s);

        assertTerminalConsistent(s, SagaStatus.SUCCEEDED);
        assertEquals(3, orderService.forwardCallCount(OrderSagaParticipants.PAYMENT),
                "物理调用 3 次（2 次失败 + 1 次恢复）");
        // 尽管物理调用 3 次，台账层面支付只生效一次——无重复副作用
        assertExactly(List.of(
                OrderSagaParticipants.INVENTORY + "#" + requestId + ":step-0",
                OrderSagaParticipants.PAYMENT + "#" + requestId + ":step-1",
                OrderSagaParticipants.SHIPPING + "#" + requestId + ":step-2"),
                ledger.forwardEntries());
        assertEquals(0, ledger.effectiveCompensateCount());
        assertEquals(CircuitBreaker.State.CLOSED,
                resilienceCaller.circuitBreaker(OrderSagaParticipants.PAYMENT).state(),
                "恢复成功后熔断器保持/回到 CLOSED");
    }

    @Test
    void circuitBreakerOpensDuringSagaAndFastFailsAfterwards() {
        // 支付持续失败：编排内 3 次尝试把熔断推到 OPEN；
        // 库存补偿也持续失败，避免补偿阶段一次成功调用把熔断重置，编排停在 COMPENSATION_FAILED 且熔断仍 OPEN。
        orderService.configureParticipant(OrderSagaParticipants.PAYMENT, MockScenario.alwaysFail());
        // 库存补偿也持续失败：补偿无法完成，熔断保持 OPEN，编排停在 COMPENSATION_FAILED
        orderService.configureCompensation(OrderSagaParticipants.INVENTORY, MockScenario.alwaysFail());

        String requestId1 = "REQ-CB-SAGA-001";
        SagaInstance s1 = orderService.submitOrder(requestId1);
        explain("熔断开启+补偿受阻", s1);

        assertTerminalConsistent(s1, SagaStatus.COMPENSATION_FAILED);
        CircuitBreaker paymentBreaker = resilienceCaller.circuitBreaker(OrderSagaParticipants.PAYMENT);
        assertEquals(CircuitBreaker.State.OPEN, paymentBreaker.state(),
                "支付持续失败后熔断器应保持 OPEN");

        // 熔断存续期内提交新订单：支付步必须被快速拒绝，且不产生真实物理调用
        int paymentCallsBefore = orderService.forwardCallCount(OrderSagaParticipants.PAYMENT);
        String requestId2 = "REQ-CB-SAGA-002";
        SagaInstance s2 = orderService.submitOrder(requestId2);
        explain("熔断期快速失败", s2);

        assertTerminalConsistent(s2, SagaStatus.COMPENSATED);
        assertEquals(paymentCallsBefore, orderService.forwardCallCount(OrderSagaParticipants.PAYMENT),
                "熔断 OPEN 期间不得对支付发起真实物理调用");
        assertTrue(s2.failureReason().contains("circuit breaker is OPEN"),
                "失败原因应可区分为熔断开启：" + s2.failureReason());
        log.info("[TEST] 判定依据：熔断开启后新编排支付步零物理调用、failureReason=circuit breaker is OPEN");
    }
}
