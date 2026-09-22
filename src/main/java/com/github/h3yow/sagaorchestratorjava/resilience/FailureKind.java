package com.github.h3yow.sagaorchestratorjava.resilience;

/**
 * 失败分类：可重试（瞬时故障：超时/瞬时错误）与不可重试（业务失败）。
 */
public enum FailureKind {
    RETRYABLE,
    NON_RETRYABLE
}
