package com.github.h3yow.sagaorchestratorjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 编排相关可配置项（重试 / 熔断 / 超时），前缀 saga.resilience。
 */
@ConfigurationProperties(prefix = "saga.resilience")
public class SagaProperties {

    private int maxAttempts = 3;
    private long backoffMillis = 50;
    private int circuitFailureThreshold = 3;
    private long circuitOpenMillis = 2000;
    private int circuitSuccessThreshold = 1;
    /** 编排侧每次下游调用的硬超时时间上限（毫秒）；<=0 表示不启用硬超时。 */
    private long callTimeoutMillis = 2000;

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public long getBackoffMillis() { return backoffMillis; }
    public void setBackoffMillis(long backoffMillis) { this.backoffMillis = backoffMillis; }

    public long getCallTimeoutMillis() { return callTimeoutMillis; }
    public void setCallTimeoutMillis(long callTimeoutMillis) { this.callTimeoutMillis = callTimeoutMillis; }

    public int getCircuitFailureThreshold() { return circuitFailureThreshold; }
    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }

    public long getCircuitOpenMillis() { return circuitOpenMillis; }
    public void setCircuitOpenMillis(long circuitOpenMillis) { this.circuitOpenMillis = circuitOpenMillis; }

    public int getCircuitSuccessThreshold() { return circuitSuccessThreshold; }
    public void setCircuitSuccessThreshold(int circuitSuccessThreshold) {
        this.circuitSuccessThreshold = circuitSuccessThreshold;
    }
}
