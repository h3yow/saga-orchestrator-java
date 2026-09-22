package com.github.h3yow.sagaorchestratorjava.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个参与方维度的熔断器（线程安全）：
 * <pre>
 *   CLOSED                    正常放行；连续失败达到阈值 ──▶ OPEN
 *   OPEN                      直接快速失败（不发起调用）；
 *                             经过 openStateDurationMs 后放行一次探测 ──▶ HALF_OPEN
 *   HALF_OPEN                 探测成功 ──▶ CLOSED（清零计数，自动恢复）；
 *                             探测失败 ──▶ OPEN（重新计时）
 * </pre>
 * 只统计调用层面的失败（超时/异常），成功即清零连续失败计数。
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String participant;
    private final ResilienceProperties properties;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAt;

    public CircuitBreaker(String participant, ResilienceProperties properties) {
        this.participant = participant;
        this.properties = properties;
    }

    public String participant() {
        return participant;
    }

    public synchronized State state() {
        if (state == State.OPEN && clock() - openedAt >= properties.getOpenStateDurationMs()) {
            state = State.HALF_OPEN;
            log.info("[CIRCUIT] {} OPEN -> HALF_OPEN (probe allowed)", participant);
        }
        return state;
    }

    /** 是否允许本次调用通过；OPEN 期间返回 false，调用方应快速失败。 */
    public boolean allowRequest() {
        return state() != State.OPEN;
    }

    public synchronized void recordSuccess() {
        if (state != State.CLOSED) {
            log.info("[CIRCUIT] {} {} -> CLOSED (recovered)", participant, state);
        }
        state = State.CLOSED;
        consecutiveFailures = 0;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= properties.getFailureThreshold()) {
            if (state != State.OPEN) {
                log.warn("[CIRCUIT] {} {} -> OPEN after {} consecutive failure(s)",
                        participant, state, consecutiveFailures);
            }
            state = State.OPEN;
            openedAt = clock();
        }
    }

    /** 抽成方法便于测试感知时间流逝（默认系统时钟）。 */
    protected long clock() {
        return System.currentTimeMillis();
    }
}
