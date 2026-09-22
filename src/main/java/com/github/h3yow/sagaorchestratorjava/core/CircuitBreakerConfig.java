package com.github.h3yow.sagaorchestratorjava.core;

/**
 * 熔断器配置。
 *
 * @param failureThreshold 滚动窗口内开启熔断的失败次数阈值
 * @param openMillis       熔断开启后保持 OPEN 的时长，之后进入 HALF_OPEN 试探
 * @param successThreshold HALF_OPEN 转 CLOSED 需要的连续成功次数
 */
public record CircuitBreakerConfig(int failureThreshold,
                                   long openMillis,
                                   int successThreshold) {
}
