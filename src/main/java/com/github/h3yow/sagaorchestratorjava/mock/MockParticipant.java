package com.github.h3yow.sagaorchestratorjava.mock;

import com.github.h3yow.sagaorchestratorjava.participant.Participant;
import com.github.h3yow.sagaorchestratorjava.participant.ParticipantLedger;
import com.github.h3yow.sagaorchestratorjava.resilience.NonRetryableException;
import com.github.h3yow.sagaorchestratorjava.resilience.RetryableException;
import com.github.h3yow.sagaorchestratorjava.saga.SagaContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本地模拟下游参与方。
 * <ul>
 *   <li>行为由 {@link MockScenario} 驱动，可在运行时切换（如先持续失败后恢复）；</li>
 *   <li>每次物理调用计数（含超时重试），FLAKY 模式据此在若干次瞬时失败后恢复；</li>
 *   <li>SLOW 模式可被超时中断（InterruptedException 视为被取消，不产生副作用）；</li>
 *   <li>真正生效前先经 {@link ParticipantLedger} 按 idempotencyKey 去重，
 *       重复物理调用（重试/重放/并发）不产生第二次副作用；</li>
 *   <li>补偿默认可靠成功（补偿也是台账幂等的）；若补偿前未正向生效则直接跳过。</li>
 * </ul>
 */
public class MockParticipant implements Participant {

    private static final Logger log = LoggerFactory.getLogger(MockParticipant.class);

    private final String name;
    private final ParticipantLedger ledger;
    private final AtomicReference<MockScenario> scenario = new AtomicReference<>(MockScenario.ok());
    /** 补偿阶段独立剧本：默认 null，表示补偿始终可靠成功。 */
    private final AtomicReference<MockScenario> compensateScenario = new AtomicReference<>();
    private final AtomicInteger forwardCallCount = new AtomicInteger();
    private final AtomicInteger compensateCallCount = new AtomicInteger();

    public MockParticipant(String name, ParticipantLedger ledger) {
        this.name = name;
        this.ledger = ledger;
    }

    @Override
    public String name() {
        return name;
    }

    public void useScenario(MockScenario next) {
        log.info("[PARTICIPANT] {} scenario -> {}", name, next);
        scenario.set(next);
    }

    public int forwardCallCount() {
        return forwardCallCount.get();
    }

    public int compensateCallCount() {
        return compensateCallCount.get();
    }

    /** 注入补偿阶段的故障剧本（如补偿也超时）；传 null 恢复为可靠补偿。 */
    public void useCompensateScenario(MockScenario next) {
        log.info("[PARTICIPANT] {} compensateScenario -> {}", name, next);
        compensateScenario.set(next);
    }

    /** 恢复为全部正常并清零调用计数（测试隔离用）。 */
    public void reset() {
        scenario.set(MockScenario.ok());
        compensateScenario.set(null);
        forwardCallCount.set(0);
        compensateCallCount.set(0);
    }

    @Override
    public void forward(String idempotencyKey, SagaContext context) {
        MockScenario s = scenario.get();
        int callNo = forwardCallCount.incrementAndGet();
        log.info("[PARTICIPANT] {} forward invoked call#{} key={} mode={}",
                name, callNo, idempotencyKey, s.mode());

        switch (s.mode()) {
            case SLOW -> sleepOrCancelled(s.slowMs(), idempotencyKey);
            case BUSINESS_FAIL -> throw new NonRetryableException(name,
                    s.businessMessage() != null ? s.businessMessage() : "business rejected");
            case ALWAYS_FAIL -> throw new RetryableException(name,
                    "transient failure (always) call#" + callNo);
            case FLAKY -> {
                if (callNo <= s.transientFailures()) {
                    throw new RetryableException(name,
                            "transient failure call#" + callNo + ", will recover");
                }
                applyForward(idempotencyKey);
            }
            case OK -> applyForward(idempotencyKey);
        }
    }

    private void applyForward(String idempotencyKey) {
        // 模拟真实下游的幂等表：重试/重放命中同一 key 时不重复生效
        boolean effective = ledger.recordForwardIfAbsent(name, idempotencyKey);
        log.info("[PARTICIPANT] {} forward applied key={} effective={}", name, idempotencyKey, effective);
    }

    private void sleepOrCancelled(long slowMs, String idempotencyKey) {
        try {
            Thread.sleep(slowMs);
        } catch (InterruptedException e) {
            // 被超时机制取消：本次调用未生效，恢复中断状态并以瞬时故障返回
            Thread.currentThread().interrupt();
            throw new RetryableException(name, "slow call interrupted/cancelled key=" + idempotencyKey, e);
        }
        applyForward(idempotencyKey);
    }

    @Override
    public void compensate(String idempotencyKey, SagaContext context) {
        int callNo = compensateCallCount.incrementAndGet();
        MockScenario cs = compensateScenario.get();
        log.info("[PARTICIPANT] {} compensate invoked call#{} key={}", name, callNo, idempotencyKey);

        // 补偿阶段的故障注入（独立于 forward 剧本）
        if (cs != null) {
            switch (cs.mode()) {
                case SLOW -> {
                    try {
                        Thread.sleep(cs.slowMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RetryableException(name, "slow compensate interrupted key=" + idempotencyKey, e);
                    }
                }
                case ALWAYS_FAIL -> throw new RetryableException(name,
                        "compensation transient failure call#" + callNo);
                default -> { /* 其它模式按成功处理 */ }
            }
        }

        // 未正向生效则没有东西可补偿；台账层面也保证重复补偿不产生重复副作用
        if (!ledger.hasForward(name, idempotencyKey)) {
            log.info("[PARTICIPANT] {} compensate skipped (no forward effect) key={}", name, idempotencyKey);
            return;
        }
        boolean effective = ledger.recordCompensationIfAbsent(name, idempotencyKey);
        log.info("[PARTICIPANT] {} compensate applied key={} effective={}", name, idempotencyKey, effective);
    }
}
