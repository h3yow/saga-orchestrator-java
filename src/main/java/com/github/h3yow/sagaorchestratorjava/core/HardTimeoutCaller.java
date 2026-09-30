package com.github.h3yow.sagaorchestratorjava.core;

import com.github.h3yow.sagaorchestratorjava.config.SagaProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 编排侧硬超时执行器：把每一次下游调用放到有界工作线程池上执行，编排驱动线程最多等待
 * {@code saga.resilience.call-timeout-millis} 毫秒。
 *
 * <p>与“参与方自报超时”的区别与兼容：
 * <ul>
 *   <li>参与方自报超时：下游在约定时间内自己判断并抛 {@link FailureType#TIMEOUT}，照旧透传；</li>
 *   <li>编排侧硬超时：下游长时间不返回（既不成功也不报错），达到时间上限后编排器
 *       {@code future.cancel(true)} 主动掐断等待并归类 {@link FailureType#HARD_TIMEOUT}，
 *       按可重试的瞬时故障处理，再走正常的重试上限与失败回滚。</li>
 * </ul>
 *
 * <p>被掐断的调用语义：取消动作不等待下游真正结束；下游工作线程收到中断后必须尽快终止，
 * 且参与方在“未确认完成”前不会登记生效，因此被掐断的调用不可能记账，不会留下已生效痕迹。
 *
 * <p>容量：工作线程数由 {@code saga.resilience.dispatch-pool-size} 控制（默认 64），
 * 不同 requestId 的调用占用不同工作线程，单个下游卡死只占用其自身的工作线程，
 * 不会把其他请求拖成接近串行。
 */
@Component
public class HardTimeoutCaller {

    private static final Logger log = LoggerFactory.getLogger(HardTimeoutCaller.class);

    private final SagaProperties properties;
    private final ExecutorService dispatchPool;

    public HardTimeoutCaller(SagaProperties properties) {
        this.properties = properties;
        int poolSize = Math.max(1, properties.getDispatchPoolSize());
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "saga-dispatch-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.dispatchPool = Executors.newFixedThreadPool(poolSize, factory);
        log.info("编排侧硬超时执行器初始化: callTimeoutMillis={}, dispatchPoolSize={}",
                properties.getCallTimeoutMillis(), poolSize);
    }

    /**
     * 在硬超时约束下执行一次下游调用。
     *
     * @throws ParticipantException 下游自报故障（TIMEOUT/TRANSIENT/BUSINESS 等原样透传）、
     *                              或编排侧硬超时 {@link FailureType#HARD_TIMEOUT}
     */
    public void invokeWithHardTimeout(ParticipantClient participant,
                                      String requestId,
                                      String operation,
                                      String direction) {
        long timeoutMillis = properties.getCallTimeoutMillis();

        // <=0：不在编排侧设硬超时，直接同步调用（仅保留参与方自报超时的兼容路径）
        if (timeoutMillis <= 0) {
            participant.invoke(requestId, operation, direction);
            return;
        }

        Future<?> future;
        try {
            future = dispatchPool.submit(() -> participant.invoke(requestId, operation, direction));
        } catch (RejectedExecutionException e) {
            throw new ParticipantException(
                    "参与方[" + participant.name() + "]调用派发被拒绝（派发池饱和）",
                    FailureType.TRANSIENT, e);
        }

        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 编排侧主动掐断：中断工作线程，停止等待；一律不视为成功
            boolean interrupted = future.cancel(true);
            String reason = "编排侧硬超时：参与方[" + participant.name() + "] " + direction
                    + " operation=" + operation + " 在 " + timeoutMillis
                    + "ms 内未返回，编排器主动掐断等待（mayInterruptIfRunning=true, cancelAccepted="
                    + interrupted + "），按可重试瞬时故障 HARD_TIMEOUT 处理";
            log.warn("[{}] {} requestId={} operation={} 判定依据: {}",
                    participant.name(), direction, requestId, operation, reason);
            throw new ParticipantException(reason, FailureType.HARD_TIMEOUT);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ParticipantException("等待参与方[" + participant.name() + "]返回时被中断",
                    FailureType.TRANSIENT, e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ParticipantException pe) {
                // 下游自报的失败（含其自行判定并上报的 TIMEOUT）原样透传，两类超时在此区分
                if (pe.failureType() == FailureType.TIMEOUT) {
                    log.warn("[{}] {} requestId={} operation={} 参与方自报超时(TIMEOUT)，与编排侧硬超时(HARD_TIMEOUT)区分: {}",
                            participant.name(), direction, requestId, operation, pe.getMessage());
                }
                throw pe;
            }
            throw new ParticipantException(
                    "参与方[" + participant.name() + "]抛出未预期异常", FailureType.TRANSIENT, cause);
        }
    }

    /** 测试诊断用：当前配置的单次调用时间上限。 */
    public long callTimeoutMillis() {
        return properties.getCallTimeoutMillis();
    }

    @PreDestroy
    public void shutdown() {
        dispatchPool.shutdownNow();
    }
}
