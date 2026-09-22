package com.github.h3yow.sagaorchestratorjava.core;

/**
 * 单个参与方步骤的执行状态。
 */
public enum StepStatus {
    /** 未执行 */
    NOT_STARTED,
    /** 正向动作成功 */
    EXECUTED,
    /** 正向动作明确失败（不可重试或重试耗尽） */
    FAILED,
    /** 已补偿成功 */
    COMPENSATED,
    /** 补偿失败 */
    COMPENSATION_FAILED
}
