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
 * 场景四：发货参与方持续瞬时失败（RETRYABLE）-> 有界重试后回滚：
 * 严格逆序补偿 支付、库存（发货本身从未生效 -> SKIPPED）。
 * 每个补偿只生效一次；重放原 requestId 不会触发第二次补偿。
 */
class SagaPersistentFailureRollbackTest extends AbstractSagaTest {

    @Test
    void alwaysFailingParticipantTriggersOrderedCompensation() {
        String requestId = "REQ-PERSIST-001";
        orderService.configureParticipant(OrderSagaParticipants.SHIPPING, MockScenario.alwaysFail());

        SagaInstance s = orderService.submitOrder(requestId);
        explain("持续失败回滚", s);

        assertTerminalConsistent(s, SagaStatus.COMPENSATED);
        assertEquals("arrange-shipping", s.failedStep());
        assertTrue(s.failureReason().startsWith("RETRYABLE"),
                "持续瞬时失败应归类 RETRYABLE，实际：" + s.failureReason());
        assertEquals(3, orderService.forwardCallCount(OrderSagaParticipants.SHIPPING),
                "可重试故障应进行 maxAttempts(3) 次尝试");

        // 正向生效：库存、支付各一次；发货从未生效
        assertExactly(List.of(
                OrderSagaParticipants.INVENTORY + "#" + requestId + ":step-0",
                OrderSagaParticipants.PAYMENT + "#" + requestId + ":step-1"),
                ledger.forwardEntries());

        // 逆序补偿：支付先、库存后；各只生效一次；发货跳过
        assertEquals(List.of(
                OrderSagaParticipants.PAYMENT + "#" + requestId + ":step-1",
                OrderSagaParticipants.INVENTORY + "#" + requestId + ":step-0"),
                ledger.compensateEntries(),
                "补偿必须严格逆序");
        assertTrue(s.allCompensated());

        // 补偿幂等：重放（重复提交同一 requestId）不允许再次补偿
        SagaInstance replayed = orderService.submitOrder(requestId);
        assertSameInstance(s, replayed);
        assertEquals(2, ledger.effectiveCompensateCount(), "重放不得产生重复补偿");
        s.stepRecords().forEach(r ->
                assertEquals(r.status().name().contains("COMPENSATED") ? Math.max(1, r.compensateAttempts()) : r.compensateAttempts(),
                        r.compensateAttempts()));
    }

    private static void assertSameInstance(SagaInstance a, SagaInstance b) {
        org.junit.jupiter.api.Assertions.assertSame(a, b);
    }
}
