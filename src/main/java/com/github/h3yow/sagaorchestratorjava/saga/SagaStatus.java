package com.github.h3yow.sagaorchestratorjava.saga;

/**
 * 编排实例状态：只能单调推进，已终结状态不可再次执行。
 * RUNNING -> SUCCEEDED / COMPENSATED / COMPENSATION_FAILED
 */
public enum SagaStatus {
    RUNNING,
    SUCCEEDED,
    COMPENSATED,
    COMPENSATION_FAILED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
