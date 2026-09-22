package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.demo.OrderSagaParticipants;
import com.github.h3yow.sagaorchestratorjava.mock.MockScenario;
import com.github.h3yow.sagaorchestratorjava.saga.SagaInstance;
import com.github.h3yow.sagaorchestratorjava.saga.SagaStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景八：正向失败触发逆序补偿，但某个已生效步骤的补偿本身也持续失败：
 * 编排进入 COMPENSATION_FAILED，在缺口处停止；流水能定位“哪个正向生效步骤未被补偿”。
 */
class SagaCompensationFailureTest extends AbstractSagaTest {

    @Test
    void compensationFailureLeavesExplicableGap() {
        // 库存正向成功；支付正向成功；发货持续失败 -> 回滚。
        // 逆序补偿时：发货 SKIP；支付补偿成功；库存补偿持续失败 -> 缺口停在库存。
        orderService.configureParticipant(OrderSagaParticipants.SHIPPING, MockScenario.alwaysFail());
        orderService.configureCompensation(OrderSagaParticipants.INVENTORY, MockScenario.alwaysFail());

        String requestId = "REQ-COMP-FAIL-001";
        SagaInstance s = orderService.submitOrder(requestId);
        explain("补偿失败缺口", s);

        assertTerminalConsistent(s, SagaStatus.COMPENSATION_FAILED);
        assertFalse(s.allCompensated(), "存在未完成补偿的缺口");

        // 支付已补偿；库存补偿失败（仍非 COMPENSATED）；发货跳过
        assertEquals("COMPENSATED", s.step(1).status().name(), "支付应已逆序补偿");
        assertEquals("SUCCEEDED", s.step(0).status().name(), "库存正向生效；补偿失败不回退正向状态，靠 compensateAttempts 暴露缺口");
        assertEquals("COMPENSATION_SKIPPED", s.step(2).status().name());

        // 补偿尝试也是有界的：maxAttempts=3
        assertEquals(3, orderService.compensateCallCount(OrderSagaParticipants.INVENTORY), "补偿同样受有界重试约束(maxAttempts=3)");
        assertEquals(1, s.step(0).compensateAttempts(), "编排层只发起一次补偿调用");

        // 已终结实例重放不会再次触发补偿尝试（不会“偷偷补试”）
        SagaInstance replayed = orderService.submitOrder(requestId);
        assertEquals(SagaStatus.COMPENSATION_FAILED, replayed.status());
        assertEquals(3, orderService.compensateCallCount(OrderSagaParticipants.INVENTORY), "终结后重放不得再次执行任何补偿");
        log.info("[TEST] 判定依据：缺口=deduct-inventory（3 次补偿尝试均失败），arrange-shipping 为正向失败根因，charge-payment 已补偿");
    }
}
