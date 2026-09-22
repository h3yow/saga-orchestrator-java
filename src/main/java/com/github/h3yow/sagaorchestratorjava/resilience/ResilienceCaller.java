package com.github.h3yow.sagaorchestratorjava.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 为下游调用叠加三道防护，顺序为：熔断准入 -> 单次超时 -> 有界重试。
 * <ul>
 *   <li>超时：调用提交到独立线程池，{@code future.get(timeoutMs)} 有界等待；
 *       超时线程会被中断（模拟参与方响应中断），超时归类为 RETRYABLE；</li>
 *   <li>重试：仅 {@link FailureKind#RETRYABLE} 重试，最多 maxAttempts 次，指数退避（封顶 10x）；
 *       {@link FailureKind#NON_RETRYABLE} 立即抛出，不浪费一次调用；</li>
 *   <li>熔断：每次尝试前先问熔断器，OPEN 直接抛 {@link CircuitBreakerOpenException} 快速失败；
 *       成功清零计数，HALF_OPEN 探测成功自动恢复 CLOSED。</li>
 * </ul>
 */
public class ResilienceCaller {

    private static final Logger log = LoggerFactory.getLogger(ResilienceCaller.class);

    private final ResilienceProperties properties;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ExecutorService pool;

    public ResilienceCaller(ResilienceProperties properties) {
        this.properties = properties;
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "downstream-call-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.pool = Executors.newCachedThreadPool(tf);
    }

    public CircuitBreaker circuitBreaker(String participant) {
        return breakers.computeIfAbsent(participant, p -> new CircuitBreaker(p, properties));
    }

    /** 供测试注入自定义熔断器（例如可控时钟）。 */
    public void registerCircuitBreaker(CircuitBreaker breaker) {
        breakers.put(breaker.participant(), breaker);
    }

    /** 清空所有参与方的熔断状态（仅用于测试隔离）。 */
    public void resetCircuitBreakers() {
        breakers.clear();
    }

    public void call(String participant, Callable<Void> action) {
        invoke(participant, action, "forward");
    }

    public void callCompensation(String participant, Callable<Void> action) {
        invoke(participant, action, "compensate");
    }

    private void invoke(String participant, Callable<Void> action, String phase) {
        CircuitBreaker breaker = circuitBreaker(participant);
        ParticipantException last = null;

        for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
            if (!breaker.allowRequest()) {
                log.warn("[CALL] {}#{} rejected by OPEN circuit breaker (attempt {})",
                        participant, phase, attempt);
                throw new CircuitBreakerOpenException(participant);
            }

            Future<Void> future = pool.submit(action);
            try {
                future.get(properties.getTimeoutMs(), TimeUnit.MILLISECONDS);
                breaker.recordSuccess();
                if (attempt > 1) {
                    log.info("[CALL] {}#{} succeeded on attempt {}", participant, phase, attempt);
                }
                return;
            } catch (TimeoutException e) {
                future.cancel(true);
                last = new RetryableException(participant,
                        "timeout after " + properties.getTimeoutMs() + "ms on attempt " + attempt);
                log.warn("[CALL] {}#{} timeout on attempt {}", participant, phase, attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RetryableException(participant, "interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof ParticipantException pe) {
                    last = pe;
                } else {
                    last = new RetryableException(participant,
                            "unexpected error on attempt " + attempt, cause);
                }
                log.warn("[CALL] {}#{} failed on attempt {} (kind={}): {}",
                        participant, phase, attempt, last.kind(), last.getMessage());
            }

            breaker.recordFailure();

            // 不可重试的业务失败：立即停止，不重试
            if (last.kind() == FailureKind.NON_RETRYABLE) {
                throw last;
            }
            // 熔断已开启：后续重试也只会快速失败，直接抛出
            if (!breaker.allowRequest()) {
                throw new CircuitBreakerOpenException(participant);
            }
            if (attempt < properties.getMaxAttempts()) {
                sleep(backoff(attempt));
            }
        }
        throw last;
    }

    private long backoff(int attempt) {
        long base = properties.getRetryBackoffMs();
        return Math.min(base * (1L << (attempt - 1)), base * 10);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
