package com.github.h3yow.sagaorchestratorjava.saga;

/**
 * 单步状态（同时描述正向与补偿两个阶段）。
 */
public enum StepStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    COMPENSATED,
    COMPENSATION_SKIPPED
}
