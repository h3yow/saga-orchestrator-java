package com.github.h3yow.sagaorchestratorjava.core;

/**
 * 下游调用失败的分类，用于区分可重试的瞬时故障与不可重试的业务失败。
 */
public enum FailureType {
    NONE,
    /** 不可重试的业务失败（如下游返回业务拒绝、余额不足） */
    BUSINESS,
    /** 调用超时（默认按瞬时故障处理，可重试） */
    TIMEOUT,
    /** 其他瞬时故障（连接抖动等，可重试） */
    TRANSIENT,
    /** 有界重试耗尽后仍失败 */
    RETRIES_EXHAUSTED,
    /** 熔断开启时快速失败 */
    CIRCUIT_OPEN
}
