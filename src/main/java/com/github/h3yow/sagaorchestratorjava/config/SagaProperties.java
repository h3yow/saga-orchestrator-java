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
    /**
     * 编排侧硬超时：每一次下游调用（单次尝试）允许等待的时间上限（毫秒）。
     * 超时后编排器主动停止等待，按可重试的瞬时故障 HARD_TIMEOUT 处理，再走正常的重试上限与失败回滚；
     * 被掐断的调用一律不算成功、不记账、不补偿为已生效。&lt;=0 表示不在编排侧设硬超时
     * （仅保留参与方自报超时这一兼容路径）。
     */
    private long callTimeoutMillis = 1000;
    /**
     * 硬超时检测的派发工作线程数：下游调用在这些工作线程上执行，编排驱动线程按时间上限等待，
     * 从而不同 requestId 之间不会因为某个下游长期不返回而互相阻塞成接近串行。
     * 容量需不低于预期混合并发度（默认 64，可扛住至少 32 线程混合提交）。
     */
    private int dispatchPoolSize = 64;

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public long getBackoffMillis() { return backoffMillis; }
    public void setBackoffMillis(long backoffMillis) { this.backoffMillis = backoffMillis; }

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

    public long getCallTimeoutMillis() { return callTimeoutMillis; }
    public void setCallTimeoutMillis(long callTimeoutMillis) {
        this.callTimeoutMillis = callTimeoutMillis;
    }

    public int getDispatchPoolSize() { return dispatchPoolSize; }
    public void setDispatchPoolSize(int dispatchPoolSize) {
        this.dispatchPoolSize = dispatchPoolSize;
    }
}
