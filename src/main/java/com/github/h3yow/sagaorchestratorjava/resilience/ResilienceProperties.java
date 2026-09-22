package com.github.h3yow.sagaorchestratorjava.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 超时 / 有界重试 / 熔断配置项（saga.resilience.*）。
 */
@Component
@ConfigurationProperties(prefix = "saga.resilience")
public class ResilienceProperties {

    /** 单次下游调用超时（毫秒）。 */
    private long timeoutMs = 200;
    /** 最大尝试次数（含首次），仅对可重试故障生效。 */
    private int maxAttempts = 3;
    /** 重试退避基数（毫秒）。 */
    private long retryBackoffMs = 20;
    /** 连续失败达到该阈值后熔断开启。 */
    private int failureThreshold = 5;
    /** 熔断开启多久后进入半开探测（毫秒）。 */
    private long openStateDurationMs = 1000;

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public long getRetryBackoffMs() { return retryBackoffMs; }
    public void setRetryBackoffMs(long retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }
    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
    public long getOpenStateDurationMs() { return openStateDurationMs; }
    public void setOpenStateDurationMs(long openStateDurationMs) { this.openStateDurationMs = openStateDurationMs; }
}
