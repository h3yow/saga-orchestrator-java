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
 * 场景五：支付返回不可重试的业务失败（NON_RETRYABLE，如余额不足/风控拒绝）：
 * 不做任何重试，立即失败并逆序回滚库存。失败原因中可区分业务失败与瞬时故障。
 */
class SagaBusinessFailureTest extends AbstractSagaTest {

    @Test
    void nonRetryableBusinessFailureFailsFastAndRollsBack() {
        String requestId = "REQ-BIZ-001";
        orderService.configureParticipant(OrderSagaParticipants.PAYMENT,
                MockScenario.businessFail("INSUFFICIENT_BALANCE"));

        SagaInstance s = orderService.submitOrder(requestId);
        explain("业务失败(不可重试)", s);

        assertTerminalConsistent(s, SagaStatus.COMPENSATED);
        assertEquals("charge-payment", s.failedStep());
        assertTrue(s.failureReason().contains("NON_RETRYABLE"),
                "应明确标记为 NON_RETRYABLE：" + s.failureReason());
        assertTrue(s.failureReason().contains("INSUFFICIENT_BALANCE"),
                "失败原因应保留业务信息：" + s.failureReason());

        // 关键：不可重试 -> 物理调用只发生 1 次（对比超时/瞬时失败的 3 次）
        assertEquals(1, orderService.forwardCallCount(OrderSagaParticipants.PAYMENT),
                "不可重试业务失败不得重试");

        assertExactly(List.of(OrderSagaParticipants.INVENTORY + "#" + requestId + ":step-0"),
                ledger.forwardEntries());
        assertExactly(List.of(OrderSagaParticipants.INVENTORY + "#" + requestId + ":step-0"),
                ledger.compensateEntries());
        assertTrue(s.allCompensated());
    }
}
