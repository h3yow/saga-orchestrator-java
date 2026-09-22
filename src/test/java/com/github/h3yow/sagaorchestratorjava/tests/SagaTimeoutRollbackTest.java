package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.demo.OrderSagaParticipants;
import com.github.h3yow.sagaorchestratorjava.mock.MockScenario;
import com.github.h3yow.sagaorchestratorjava.saga.SagaInstance;
import com.github.h3yow.sagaorchestratorjava.saga.SagaStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景三：支付参与方持续慢调用 -> 每次都超过超时阈值（RETRYABLE），
 * 有界重试 maxAttempts 次后放弃，编排逆序补偿：库存被回补，支付从未生效。
 * 断言：无重复生效、无漏补偿、失败原因中可区分“超时”。
 */
class SagaTimeoutRollbackTest extends AbstractSagaTest {

    @Test
    void timeoutAfterBoundedRetriesThenRollback() {
        String requestId = "REQ-TIMEOUT-001";
        // 500ms 远超配置的 200ms 超时：每次尝试都会超时
        orderService.configureParticipant(OrderSagaParticipants.PAYMENT, MockScenario.slow(500));

        SagaInstance s = orderService.submitOrder(requestId);
        explain("超时重试后回滚", s);

        assertTerminalConsistent(s, SagaStatus.COMPENSATED);
        assertEquals("charge-payment", s.failedStep());
        assertTrue(s.failureReason().contains("timeout"),
                "失败原因应可区分为超时，实际：" + s.failureReason());

        // 库存生效 1 次；支付 3 次尝试均超时、从未生效；发货未执行
        assertEquals(3, orderService.forwardCallCount(OrderSagaParticipants.PAYMENT), "应恰好进行 maxAttempts(3) 次物理尝试");
        assertEquals(1, s.step(1).forwardAttempts(), "编排层只发起一次调用，重试在韧性层内部完成");
        assertExactly(List.of(OrderSagaParticipants.INVENTORY + "#" + requestId + ":step-0"),
                ledger.forwardEntries());

        // 逆序补偿：只有库存需要补偿且只补偿一次（支付被跳过、发货跳过）
        assertExactly(List.of(OrderSagaParticipants.INVENTORY + "#" + requestId + ":step-0"),
                ledger.compensateEntries());
        assertEquals(1, s.step(0).compensateAttempts());
        assertEquals("COMPENSATION_SKIPPED", s.step(1).status().name());
        assertEquals("COMPENSATION_SKIPPED", s.step(2).status().name());
        assertTrue(s.allCompensated(), "不允许漏补偿");
    }
}
