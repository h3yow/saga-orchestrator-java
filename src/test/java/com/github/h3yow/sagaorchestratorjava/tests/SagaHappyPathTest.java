package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.demo.OrderSagaParticipants;
import com.github.h3yow.sagaorchestratorjava.saga.SagaInstance;
import com.github.h3yow.sagaorchestratorjava.saga.SagaStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 场景一：全部参与方正常 -> SUCCEEDED，三步各生效一次、无补偿。
 */
class SagaHappyPathTest extends AbstractSagaTest {

    @Test
    void allStepsSucceed() {
        String requestId = "REQ-HAPPY-001";
        SagaInstance s = orderService.submitOrder(requestId);
        explain("成功路径", s);

        assertTerminalConsistent(s, SagaStatus.SUCCEEDED);
        assertExactly(List.of(
                OrderSagaParticipants.INVENTORY + "#" + requestId + ":step-0",
                OrderSagaParticipants.PAYMENT + "#" + requestId + ":step-1",
                OrderSagaParticipants.SHIPPING + "#" + requestId + ":step-2"),
                ledger.forwardEntries());
        assertEquals(0, ledger.effectiveCompensateCount());
    }
}
