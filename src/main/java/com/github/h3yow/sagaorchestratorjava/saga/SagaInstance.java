package com.github.h3yow.sagaorchestratorjava.saga;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排实例：以 requestId 唯一标识。
 *
 * 状态机（单调推进，不可逆）：
 * <pre>
 *   RUNNING ──全部成功──────────────▶ SUCCEEDED
 *           └─某步失败且补偿完成────▶ COMPENSATED
 *           └─某步失败但补偿也失败──▶ COMPENSATION_FAILED
 * </pre>
 * 已终结的实例不会再被执行；并发与重放由 SagaRepository 的 per-requestId 锁保证。
 */
public class SagaInstance {

    private final String requestId;
    private final String sagaType;
    private final SagaContext context;
    private final List<StepRecord> stepRecords;
    private volatile SagaStatus status = SagaStatus.RUNNING;
    private volatile String failureReason;
    private volatile String failedStep;

    public SagaInstance(String requestId, String sagaType, SagaContext context, int stepCount) {
        this.requestId = requestId;
        this.sagaType = sagaType;
        this.context = context;
        this.stepRecords = new ArrayList<>(stepCount);
    }

    /** 仅在持锁创建实例时初始化流水，保证步数固定。 */
    public void initSteps(List<String> stepNames) {
        if (stepRecords.isEmpty()) {
            for (int i = 0; i < stepNames.size(); i++) {
                stepRecords.add(new StepRecord(i, stepNames.get(i)));
            }
        }
    }

    public String requestId() {
        return requestId;
    }

    public String sagaType() {
        return sagaType;
    }

    public SagaContext context() {
        return context;
    }

    public List<StepRecord> stepRecords() {
        return stepRecords;
    }

    public StepRecord step(int index) {
        return stepRecords.get(index);
    }

    public SagaStatus status() {
        return status;
    }

    public String failureReason() {
        return failureReason;
    }

    public String failedStep() {
        return failedStep;
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public void markSucceeded() {
        if (status != SagaStatus.RUNNING) {
            throw new IllegalStateException("saga already terminal: " + requestId + " -> " + status);
        }
        this.status = SagaStatus.SUCCEEDED;
    }

    public void markCompensated(String failedStep, String reason) {
        if (status != SagaStatus.RUNNING) {
            throw new IllegalStateException("saga already terminal: " + requestId + " -> " + status);
        }
        this.failedStep = failedStep;
        this.failureReason = reason;
        this.status = SagaStatus.COMPENSATED;
    }

    public void markCompensationFailed(String failedStep, String reason, String compensationReason) {
        if (status != SagaStatus.RUNNING) {
            throw new IllegalStateException("saga already terminal: " + requestId + " -> " + status);
        }
        this.failedStep = failedStep;
        this.failureReason = reason;
        this.status = SagaStatus.COMPENSATION_FAILED;
    }

    /** 全部步骤都已完成补偿（含显式跳过），用于判定“无漏补偿”。 */
    public boolean allCompensated() {
        return stepRecords.stream().allMatch(StepRecord::isCompensated);
    }
}
