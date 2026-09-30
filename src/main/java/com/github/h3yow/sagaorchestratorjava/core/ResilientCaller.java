package com.github.h3yow.sagaorchestratorjava.core;

import com.github.h3yow.sagaorchestratorjava.config.SagaProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对下游参与方调用统一施加弹性保护：
 * <ol>
 *   <li><b>熔断</b>：参与方维度的熔断器，OPEN 时直接快速失败（{@link FailureType#CIRCUIT_OPEN}），
 *       不发起调用；OPEN 超时后放一次试探，成功自动恢复。</li>
 *   <li><b>编排侧硬超时</b>：每次下游调用都在独立工作线程上执行，调用方最多等待
 *       {@code saga.resilience.call-timeout-millis}；下游长时间不返回时，编排器停止等待并
 *       {@code cancel(true)} 中断工作线程，按 {@link FailureType#ORCHESTRATOR_TIMEOUT}
 *             （可重试瞬时故障）处理。被掐断的调用一律不算成功、不记账、不产生已生效痕迹。</li>
 *   <li><b>有界重试</b>：对 {@link FailureType#TIMEOUT}（参与方自报超时）/
 *       {@link FailureType#ORCHESTRATOR_TIMEOUT}（编排侧掐断）/ {@link FailureType#TRANSIENT}
 *       重试，固定退避，最多 {@code maxAttempts} 次；耗尽后以
 *       {@link FailureType#RETRIES_EXHAUSTED} 失败，消息中保留最后一次的失败分类以区分两类超时。</li>
 *   <li><b>不可重试失败</b>：{@link FailureType#BUSINESS} 立即上抛，不浪费调用。</li>
 * </ol>
 */
@Component
public class ResilientCaller {

    private static final Logger log = LoggerFactory.getLogger(ResilientCaller.class);

    private final SagaProperties properties;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    /** 下游调用隔离线程池：daemon 线程，保证被掐断的调用不阻塞 JVM 退出。 */
    private final ExecutorService invocationPool = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "saga-downstream-call-" + index.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    public ResilientCaller(SagaProperties properties) {
        this.properties = properties;
    }

    /** 由全局配置构造默认重试策略。 */
    public RetryPolicy defaultRetryPolicy() {
        return new RetryPolicy(properties.getMaxAttempts(), properties.getBackoffMillis());
    }

    /**
     * 在硬超时、熔断与重试包装下执行一次参与方操作。
     *
     * @throws ParticipantException 业务失败 / 重试耗尽 / 熔断开启 / 编排侧硬超时（在重试包装外不直接抛）
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
                invokeOnce(participant, requestId, operation, direction, attempt);
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

                // 瞬时故障 / 两类超时：计入熔断失败统计
                breaker.recordFailure();

                if (attempt < maxAttempts) {
                    log.warn("[{}] {} requestId={} operation={} 第{}/{}次尝试失败({})，{}ms后重试: {}",
                            key, direction, requestId, operation, attempt, maxAttempts,
                            type, retryPolicy.backoffMillis(), e.getMessage());
                    sleep(retryPolicy.backoffMillis());
                } else {
                    log.warn("[{}] {} requestId={} operation={} 重试{}次后仍失败(末次分类={})",
                            key, direction, requestId, operation, maxAttempts, type);
                }
            }
        }

        throw new ParticipantException(
                "参与方[" + key + "]在" + maxAttempts + "次尝试后仍失败(末次分类="
                        + last.failureType() + "): " + last.getMessage(),
                FailureType.RETRIES_EXHAUSTED, last);
    }

    /**
     * 执行单次调用并施加编排侧硬超时。
     *
     * <p>调用在独立线程上发生：若在时间上限内返回，正常传递结果/异常；若到点仍未返回，
     * 调用方线程立即以 {@link FailureType#ORCHESTRATOR_TIMEOUT} 失败并中断工作线程。
     * 被掐断的调用不可能回到这里报告成功，因此编排器绝不会把超时当成成功；下游是否真正生效
     * 由参与方侧幂等与“动作成功后才登记”的顺序共同保证（本工程的模拟下游在挂起期间不产生副作用）。
     */
    private void invokeOnce(ParticipantClient participant,
                            String requestId,
                            String operation,
                            String direction,
                            int attempt) {
        String key = participant.name();
        long timeoutMillis = properties.getCallTimeoutMillis();
        Future<?> future = invocationPool.submit(() ->
                participant.invoke(requestId, operation, direction));
        try {
            if (timeoutMillis <= 0) {
                future.get();
            } else {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            }
        } catch (TimeoutException e) {
            // 编排侧硬超时：停止等待、中断挂起的工作线程，调用按可重试瞬时故障处理
            future.cancel(true);
            String reason = "编排侧硬超时：参与方[" + key + "]在" + timeoutMillis
                    + "ms内未返回，已掐断第" + attempt + "次调用(ORCHESTRATOR_TIMEOUT)"
                    + "，该次调用不算成功、不记账";
            log.warn("[{}] {} requestId={} operation={} 判定依据: {}",
                    key, direction, requestId, operation, reason);
            throw new ParticipantException(reason, FailureType.ORCHESTRATOR_TIMEOUT);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ParticipantException pe) {
                throw pe;
            }
            throw new ParticipantException(
                    "参与方[" + key + "]调用异常: " + cause,
                    FailureType.TRANSIENT, cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ParticipantException("等待参与方[" + key + "]返回时被中断",
                    FailureType.TRANSIENT, e);
        }
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

    @PreDestroy
    void shutdown() {
        invocationPool.shutdownNow();
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
