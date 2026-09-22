package com.github.h3yow.sagaorchestratorjava.core;

/**
 * 单个步骤在某个 Saga 实例中的持久化状态。
 * 字段更新由 SagaInstance 在持锁状态下完成，保证并发下状态一致。
 */
public class StepState {

    private final String stepName;
    private StepStatus status = StepStatus.NOT_STARTED;
    /** 正向失败的分类（未失败为 NONE） */
    private FailureType failureType = FailureType.NONE;
    /** 失败原因描述（可解释/可区分） */
    private String failureMessage;

    public StepState(String stepName) {
        this.stepName = stepName;
    }

    public String stepName() {
        return stepName;
    }

    public StepStatus status() {
        return status;
    }

    public FailureType failureType() {
        return failureType;
    }

    public String failureMessage() {
        return failureMessage;
    }

    /** 正向动作已成功生效（重复调用时保持幂等，不回退状态）。 */
    public void markExecuted() {
        this.status = StepStatus.EXECUTED;
        this.failureType = FailureType.NONE;
        this.failureMessage = null;
    }

    /** 正向动作明确失败，记录分类与原因。 */
    public void markFailed(FailureType type, String message) {
        this.status = StepStatus.FAILED;
        this.failureType = type;
        this.failureMessage = message;
    }

    /** 补偿成功（幂等：已 COMPENSATED 不再变化）。 */
    public void markCompensated() {
        this.status = StepStatus.COMPENSATED;
    }

    /** 补偿本身失败，需要人工介入。 */
    public void markCompensationFailed(FailureType type, String message) {
        this.status = StepStatus.COMPENSATION_FAILED;
        this.failureType = type;
        this.failureMessage = message;
    }

    /** 正向动作是否已经生效（决定该步是否需要补偿）。 */
    public boolean hasExecuted() {
        return status == StepStatus.EXECUTED
                || status == StepStatus.COMPENSATED
                || status == StepStatus.COMPENSATION_FAILED;
    }
}
