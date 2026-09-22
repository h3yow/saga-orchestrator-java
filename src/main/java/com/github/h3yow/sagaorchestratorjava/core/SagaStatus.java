package com.github.h3yow.sagaorchestratorjava.core;

/**
 * 编排（Saga 实例）的生命周期状态。状态只允许单调向前推进。
 */
public enum SagaStatus {
    /** 已受理，尚未开始执行 */
    PENDING,
    /** 正向执行中 */
    RUNNING,
    /** 正在逆序补偿 */
    COMPENSATING,
    /** 全部成功生效（终态） */
    COMPLETED,
    /** 已全部回退（终态） */
    COMPENSATED,
    /** 补偿过程本身失败（终态，需人工介入） */
    COMPENSATION_FAILED
}
