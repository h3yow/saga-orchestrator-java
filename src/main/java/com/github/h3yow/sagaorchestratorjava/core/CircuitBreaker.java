package com.github.h3yow.sagaorchestratorjava.core;

/**
 * 单个参与方维度的熔断器。
 *
 * <p>规则：
 * <ul>
 *   <li>CLOSED：统计最近 {@code failureThreshold} 次调用，若全部失败则转 OPEN；
 *       期间任意一次成功立即清零计数。</li>
 *   <li>OPEN：持续 {@code openMillis}，期间 {@link #allowRequest()} 返回 false，调用方快速失败；
 *       超过时长后放行一次试探并进入 HALF_OPEN。</li>
 *   <li>HALF_OPEN：连续 {@code successThreshold} 次成功后回到 CLOSED；任意一次失败立即回到 OPEN。</li>
 * </ul>
 * 所有方法 synchronized，保证多线程并发调用时状态一致。
 */
public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final CircuitBreakerConfig config;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private int halfOpenSuccesses = 0;
    private long openedAtMillis = 0L;

    public CircuitBreaker(CircuitBreakerConfig config) {
        this.config = config;
    }

    public synchronized State state() {
        // OPEN 超时惰性转 HALF_OPEN
        if (state == State.OPEN
                && System.currentTimeMillis() - openedAtMillis >= config.openMillis()) {
            state = State.HALF_OPEN;
            halfOpenSuccesses = 0;
        }
        return state;
    }

    /**
     * 是否允许调用。CLOSED 放行；OPEN 未超时拒绝；OPEN 已超时则转 HALF_OPEN 放行一次试探。
     */
    public synchronized boolean allowRequest() {
        return state() != State.OPEN;
    }

    public synchronized void recordSuccess() {
        switch (state) {
            case CLOSED -> consecutiveFailures = 0;
            case HALF_OPEN -> {
                halfOpenSuccesses++;
                if (halfOpenSuccesses >= config.successThreshold()) {
                    toClosed();
                }
            }
            case OPEN -> {
                // 理论上 OPEN 期间不产生调用结果；防御性地不做状态变更
            }
        }
    }

    public synchronized void recordFailure() {
        switch (state) {
            case CLOSED -> {
                consecutiveFailures++;
                if (consecutiveFailures >= config.failureThreshold()) {
                    toOpen();
                }
            }
            case HALF_OPEN -> toOpen();
            case OPEN -> {
                // 保持 OPEN
            }
        }
    }

    /** OPEN 打开的剩余时间（毫秒），用于快速失败时给出判定依据；非 OPEN 返回 0。 */
    public synchronized long remainingOpenMillis() {
        if (state() != State.OPEN) {
            return 0L;
        }
        long elapsed = System.currentTimeMillis() - openedAtMillis;
        return Math.max(0L, config.openMillis() - elapsed);
    }

    private void toOpen() {
        state = State.OPEN;
        openedAtMillis = System.currentTimeMillis();
        halfOpenSuccesses = 0;
    }

    private void toClosed() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        halfOpenSuccesses = 0;
    }
}
