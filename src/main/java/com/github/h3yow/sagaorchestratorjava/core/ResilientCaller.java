package com.github.h3yow.sagaorchestratorjava.core;

import com.github.h3yow.sagaorchestratorjava.config.SagaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 对下游参与方调用统一施加弹性保护：
 * <ol>
 *   <li><b>熔断</b>：参与方维度的熔断器，OPEN 时直接快速失败（{@link FailureType#CIRCUIT_OPEN}），
 *       不发起调用；OPEN 超时后放一次试探，成功自动恢复。</li>
 *   <li><b>有界重试</b>：仅对 {@link FailureType#TIMEOUT} / {@link FailureType#TRANSIENT} 重试，
 *       固定退避，最多 {@code maxAttempts} 次；耗尽后以 {@link FailureType#RETRIES_EXHAUSTED} 失败。</li>
 *   <li><b>不可重试失败</b>：{@link FailureType#BUSINESS} 立即上抛，不浪费调用。</li>
 * </ol>
 * 超时本身由参与方模拟（sleep 后抛 TIMEOUT），语义与真实下游超时一致。
 */
@Component
public class ResilientCaller {

    private static final Logger log = LoggerFactory.getLogger(ResilientCaller.class);

    private final SagaProperties properties;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public ResilientCaller(SagaProperties properties) {
        this.properties = properties;
    }

    /** 由全局配置构造默认重试策略。 */
    public RetryPolicy defaultRetryPolicy() {
        return new RetryPolicy(properties.getMaxAttempts(), properties.getBackoffMillis());
    }

    /**
     * 在熔断与重试包装下执行一次参与方操作。
     *
     * @throws ParticipantException 业务失败 / 重试耗尽 / 熔断开启
     */
    public void callWithResilience(ParticipantClient participant,
                                   String requestId,
                                   String operation,
                                   String direction,
                                   RetryPolicy retryPolicy) {
        String key = participant.name();
        CircuitBreaker breaker = breakers.computeIfAbsent(key,
                k -> new CircuitBreaker(circuitConfig()));

        if (!breaker.allowRequest()) {
            String reason = "参与方[" + key + "]熔断开启(OPEN)，快速失败，剩余"
                    + breaker.remainingOpenMillis() + "ms";
            log.warn("[{}] {} requestId={} operation={} 判定依据: {}",
                    key, direction, requestId, operation, reason);
            throw new ParticipantException(reason, FailureType.CIRCUIT_OPEN);
        }

        int maxAttempts = Math.max(1, retryPolicy.maxAttempts());
        ParticipantException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                participant.invoke(requestId, operation, direction);
                breaker.recordSuccess();
                if (attempt > 1) {
                    log.info("[{}] {} requestId={} operation={} 第{}次尝试成功（瞬时故障已恢复）",
                            key, direction, requestId, operation, attempt);
                }
                return;
            } catch (ParticipantException e) {
                last = e;
                FailureType type = e.failureType();

                // 业务失败不可重试，立即失败
                if (type == FailureType.BUSINESS) {
                    breaker.recordFailure();
                    log.warn("[{}] {} requestId={} operation={} 业务失败，不重试: {}",
                            key, direction, requestId, operation, e.getMessage());
                    throw e;
                }

                // 熔断开启（参与方内部也可能直接拒绝）不重试
                if (type == FailureType.CIRCUIT_OPEN) {
                    throw e;
                }

                // 瞬时故障 / 超时：计入熔断失败统计
                breaker.recordFailure();

                if (attempt < maxAttempts) {
                    log.warn("[{}] {} requestId={} operation={} 第{}/{}次尝试失败({})，{}ms后重试: {}",
                            key, direction, requestId, operation, attempt, maxAttempts,
                            type, retryPolicy.backoffMillis(), e.getMessage());
                    sleep(retryPolicy.backoffMillis());
                } else {
                    log.warn("[{}] {} requestId={} operation={} 重试{}次后仍失败({})",
                            key, direction, requestId, operation, maxAttempts, type);
                }
            }
        }

        throw new ParticipantException(
                "参与方[" + key + "]在" + maxAttempts + "次尝试后仍失败: " + last.getMessage(),
                FailureType.RETRIES_EXHAUSTED, last);
    }

    /** 查询某参与方当前熔断状态（测试/诊断使用）。 */
    public CircuitBreaker.State breakerState(String participantName) {
        CircuitBreaker breaker = breakers.get(participantName);
        return breaker == null ? CircuitBreaker.State.CLOSED : breaker.state();
    }

    /** 重置全部熔断器（测试隔离使用）。 */
    public void reset() {
        breakers.clear();
    }

    private CircuitBreakerConfig circuitConfig() {
        return new CircuitBreakerConfig(
                properties.getCircuitFailureThreshold(),
                properties.getCircuitOpenMillis(),
                properties.getCircuitSuccessThreshold());
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParticipantException("重试等待被中断", FailureType.TRANSIENT, e);
        }
    }
}
