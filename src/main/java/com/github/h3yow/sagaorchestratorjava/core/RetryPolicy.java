package com.github.h3yow.sagaorchestratorjava.core;

/**
 * 有界重试策略。
 *
 * @param maxAttempts    最大尝试次数（含首次），&lt;=1 表示不重试
 * @param backoffMillis  固定退避时间（毫秒）
 */
public record RetryPolicy(int maxAttempts, long backoffMillis) {
}
