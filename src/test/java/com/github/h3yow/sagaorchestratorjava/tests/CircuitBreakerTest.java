package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.resilience.CircuitBreaker;
import com.github.h3yow.sagaorchestratorjava.resilience.CircuitBreakerOpenException;
import com.github.h3yow.sagaorchestratorjava.resilience.ParticipantException;
import com.github.h3yow.sagaorchestratorjava.resilience.ResilienceCaller;
import com.github.h3yow.sagaorchestratorjava.resilience.ResilienceProperties;
import com.github.h3yow.sagaorchestratorjava.resilience.RetryableException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景六：熔断开启（持续失败快速失败、不阻塞）与故障恢复后自动放行。
 * 使用可控时钟，无需真实等待 openStateDurationMs。
 */
class CircuitBreakerTest {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerTest.class);

    static class MutableClockBreaker extends CircuitBreaker {
        private final AtomicLong now = new AtomicLong();

        MutableClockBreaker(String participant, ResilienceProperties properties) {
            super(participant, properties);
        }

        @Override
        protected long clock() {
            return now.get();
        }

        void advance(long ms) {
            now.addAndGet(ms);
        }
    }

    private ResilienceProperties props() {
        ResilienceProperties p = new ResilienceProperties();
        p.setTimeoutMs(500);
        p.setMaxAttempts(1);            // 本测试聚焦熔断，关闭重试
        p.setFailureThreshold(3);
        p.setOpenStateDurationMs(1000);
        return p;
    }

    @Test
    void opensAfterThresholdAndFastFailsThenRecovers() {
        ResilienceProperties properties = props();
        ResilienceCaller caller = new ResilienceCaller(properties);
        // 必须在任何 call 之前注册，否则会先创建默认时钟的熔断器
        MutableClockBreaker cb = new MutableClockBreaker("downstream-x", properties);
        caller.registerCircuitBreaker(cb);

        AtomicInteger physicalCalls = new AtomicInteger();
        var alwaysFail = (java.util.concurrent.Callable<Void>) () -> {
            physicalCalls.incrementAndGet();
            throw new RetryableException("downstream-x", "boom");
        };

        // 连续失败达到阈值 3 次 -> OPEN
        for (int i = 0; i < 3; i++) {
            try {
                caller.call("downstream-x", alwaysFail);
            } catch (ParticipantException expected) {
                // 第 1~3 次：真实调用失败；第 3 次触发熔断并以 CircuitBreakerOpenException 快速失败
                log.info("[TEST] 第 {} 次调用结束: {}", i + 1, expected.getClass().getSimpleName());
            }
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        log.info("[TEST] 熔断已开启，连续失败 3 次，physicalCalls={}", physicalCalls.get());

        // OPEN 期间调用：不发起物理调用，直接快速失败
        for (int i = 0; i < 5; i++) {
            try {
                caller.call("downstream-x", alwaysFail);
                throw new AssertionError("OPEN 期间必须快速失败");
            } catch (CircuitBreakerOpenException e) {
                // 期望：快速失败
            }
        }
        assertEquals(3, physicalCalls.get(),
                "熔断开启后不得再发起真实下游调用（快速失败，不阻塞）");
        log.info("[TEST] OPEN 期间 5 次调用全部被快速拒绝，physicalCalls 仍为 3");

        // 时间流逝超过 openStateDurationMs -> HALF_OPEN 放行一次探测；探测仍失败 -> 重新 OPEN
        cb.advance(1001);
        try {
            caller.call("downstream-x", alwaysFail);
        } catch (ParticipantException e) {
            // 探测失败：HALF_OPEN -> 重新 OPEN，并快速失败
            log.info("[TEST] 半开探测结束: {}", e.getClass().getSimpleName());
        }
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertEquals(4, physicalCalls.get(), "HALF_OPEN 应只放行一次探测");
        log.info("[TEST] 半开探测失败 -> 重新 OPEN，physicalCalls={}", physicalCalls.get());

        // 再经过一个冷却窗口，下游恢复：探测成功 -> CLOSED，后续调用正常
        cb.advance(1001);
        AtomicInteger successCalls = new AtomicInteger();
        caller.call("downstream-x", (java.util.concurrent.Callable<Void>) () -> {
            successCalls.incrementAndGet();
            physicalCalls.incrementAndGet();
            return null;
        });
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        caller.call("downstream-x", (java.util.concurrent.Callable<Void>) () -> {
            successCalls.incrementAndGet();
            return null;
        });
        assertEquals(2, successCalls.get(), "恢复后调用应正常通过");
        log.info("[TEST] 半开探测成功 -> CLOSED 自动恢复，连续 2 次成功调用通过");
        assertTrue(true, "判定依据：OPEN 快速失败且无物理调用；HALF_OPEN 单次探测；成功后 CLOSED 恢复");
    }

    @Test
    void breakerIsPerParticipant() {
        ResilienceProperties properties = props();
        ResilienceCaller caller = new ResilienceCaller(properties);
        MutableClockBreaker cbA = new MutableClockBreaker("svc-a", properties);
        MutableClockBreaker cbB = new MutableClockBreaker("svc-b", properties);
        caller.registerCircuitBreaker(cbA);
        caller.registerCircuitBreaker(cbB);

        for (int i = 0; i < 3; i++) {
            try {
                caller.call("svc-a", () -> {
                    throw new RetryableException("svc-a", "boom");
                });
            } catch (ParticipantException ignored) {
            }
        }
        assertEquals(CircuitBreaker.State.OPEN, cbA.state());
        // svc-b 不受 svc-a 影响
        assertEquals(CircuitBreaker.State.CLOSED, cbB.state());
        AtomicInteger bCalls = new AtomicInteger();
        caller.call("svc-b", () -> {
            bCalls.incrementAndGet();
            return null;
        });
        assertEquals(1, bCalls.get());
        log.info("[TEST] 熔断按参与方隔离：svc-a OPEN 不影响 svc-b 正常调用");
    }
}
