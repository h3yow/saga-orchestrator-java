package com.github.h3yow.sagaorchestratorjava.core;

/**
 * 单个步骤在某个 Saga 实例中的持久化状态。
 * 字段更新由 SagaInstance 在持锁状态下完成，保证并发下状态一致。
 */
public class StepState {

    private final String stepName;
    // 均以 volatile 发布：驱动线程在实例锁内写入，任意并发查询线程可无锁读到最新单调状态
    private volatile StepStatus status = StepStatus.NOT_STARTED;
    /** 正向失败的分类（未失败为 NONE） */
    private volatile FailureType failureType = FailureType.NONE;
    /** 失败原因描述（可解释/可区分） */
    private volatile String failureMessage;
    /** 最终一次尝试的底层失败分类（重试耗尽时保留 TIMEOUT/HARD_TIMEOUT/TRANSIENT 以区分判定依据） */
    private volatile FailureType rootCauseType = FailureType.NONE;

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

    /** 最后一次尝试的底层失败分类（用于在重试耗尽后区分超时来源）。 */
    public FailureType rootCauseType() {
        return rootCauseType;
    }

    /** 正向动作已成功生效（重复调用时保持幂等，不回退状态）。 */
    public void markExecuted() {
        this.status = StepStatus.EXECUTED;
        this.failureType = FailureType.NONE;
        this.rootCauseType = FailureType.NONE;
        this.failureMessage = null;
    }

    /**
     * 正向动作明确失败，记录分类与原因。
     *
     * @param type          最终失败分类（如 BUSINESS / RETRIES_EXHAUSTED / CIRCUIT_OPEN）
     * @param rootCauseType 最后一次尝试的底层分类（重试耗尽时用于区分 TIMEOUT 与 HARD_TIMEOUT；
     *                      无更底层分类时与 type 相同）
     */
    public void markFailed(FailureType type, FailureType rootCauseType, String message) {
        this.status = StepStatus.FAILED;
        this.failureType = type;
        this.rootCauseType = rootCauseType == null ? FailureType.NONE : rootCauseType;
        this.failureMessage = message;
    }

    /** 补偿成功（幂等：已 COMPENSATED 不再变化）。 */
    public void markCompensated() {
        this.status = StepStatus.COMPENSATED;
    }

    /** 补偿本身失败，需要人工介入。 */
    public void markCompensationFailed(FailureType type, FailureType rootCauseType, String message) {
        this.status = StepStatus.COMPENSATION_FAILED;
        this.failureType = type;
        this.rootCauseType = rootCauseType == null ? FailureType.NONE : rootCauseType;
        this.failureMessage = message;
    }

    /** 正向动作是否已经生效（决定该步是否需要补偿）。 */
    public boolean hasExecuted() {
        return status == StepStatus.EXECUTED
                || status == StepStatus.COMPENSATED
                || status == StepStatus.COMPENSATION_FAILED;
    }
}
