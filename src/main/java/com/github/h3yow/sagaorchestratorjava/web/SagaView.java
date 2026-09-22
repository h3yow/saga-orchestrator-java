package com.github.h3yow.sagaorchestratorjava.web;

import com.github.h3yow.sagaorchestratorjava.saga.SagaInstance;
import com.github.h3yow.sagaorchestratorjava.saga.StepRecord;

import java.util.List;

/**
 * 编排状态对外视图（查询/重放返回）：包含每一步的执行/补偿尝试次数与失败原因，
 * 保证状态可解释、单调推进。
 */
public record SagaView(
        String requestId,
        String sagaType,
        String status,
        String failedStep,
        String failureReason,
        List<StepView> steps) {

    public record StepView(String name, String status, int forwardAttempts,
                           int compensateAttempts, String failureReason) {
    }

    public static SagaView from(SagaInstance s) {
        List<StepView> steps = s.stepRecords().stream()
                .map(SagaView::stepView)
                .toList();
        return new SagaView(s.requestId(), s.sagaType(), s.status().name(),
                s.failedStep(), s.failureReason(), steps);
    }

    private static StepView stepView(StepRecord r) {
        return new StepView(r.name(), r.status().name(), r.forwardAttempts(),
                r.compensateAttempts(), r.failureReason());
    }
}
