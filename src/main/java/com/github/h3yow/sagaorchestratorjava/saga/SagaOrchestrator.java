package com.github.h3yow.sagaorchestratorjava.saga;

import com.github.h3yow.sagaorchestratorjava.resilience.FailureKind;
import com.github.h3yow.sagaorchestratorjava.resilience.ParticipantException;
import com.github.h3yow.sagaorchestratorjava.resilience.ResilienceCaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 编排器：实现本地可运行的分布式事务编排（编排式 Saga）。
 *
 * <p>正向协议：按 {@link SagaDefinition} 的步骤顺序依次调用参与方；
 * 每一步的物理调用都经过 {@link ResilienceCaller}（超时/重试/熔断），
 * 参与方自身按 idempotencyKey 幂等。任一步彻底失败（重试用尽、不可重试业务失败或熔断开启）后，
 * 对“已经正向生效”的步骤按严格逆序补偿，最终要么 {@code SUCCEEDED} 要么
 * {@code COMPENSATED}（补偿本身失败则为 {@code COMPENSATION_FAILED}，流水可定位缺口）。
 *
 * <p>幂等与重放：以 requestId 为幂等键，每个 requestId 一把锁。
 * 重复提交（含超时重试、并发到达）在锁上排队，首个线程执行，其余线程直接看到
 * 既有结果——已终结实例不会被再次执行；{@link #get(String)} 永远读到单调推进的状态。
 *
 * <p>补偿规则：补偿与正向使用同一 idempotencyKey 派生，台账保证补偿只生效一次；
 * 未生效的步骤显式标记 COMPENSATION_SKIPPED，不允许漏补偿或重复补偿。
 */
@Component
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final SagaRepository repository;
    private final ResilienceCaller caller;

    public SagaOrchestrator(SagaRepository repository, ResilienceCaller caller) {
        this.repository = repository;
        this.caller = caller;
    }

    /**
     * 提交（或重放）一次编排。
     *
     * @return 编排实例；重复提交返回的是同一个实例及其最终状态
     */
    public SagaInstance submit(String requestId, SagaDefinition definition, SagaContext context) {
        Object lock = repository.lockFor(requestId);
        synchronized (lock) {
            SagaInstance instance = repository.getOrCreate(requestId, definition, context);
            if (instance.isTerminal()) {
                // 已终结：重放直接返回，绝不再次执行，避免重复副作用
                log.info("[SAGA] requestId={} replay terminal status={}, no re-execution",
                        requestId, instance.status());
                return instance;
            }
            runSaga(instance, definition);
            return instance;
        }
    }

    public Optional<SagaInstance> get(String requestId) {
        return repository.find(requestId);
    }

    private void runSaga(SagaInstance instance, SagaDefinition definition) {
        String requestId = instance.requestId();
        log.info("[SAGA] requestId={} type={} start, {} step(s)",
                requestId, definition.type(), definition.steps().size());

        int failedAt = -1;
        ParticipantException failure = null;

        for (int i = 0; i < definition.steps().size(); i++) {
            SagaStep step = definition.steps().get(i);
            StepRecord rec = instance.step(i);
            String fwdKey = forwardKey(requestId, i);
            int attempt = rec.recordForwardAttempt();
            log.info("[SAGA] requestId={} forward step#{} '{}' attempt={}",
                    requestId, i, step.name(), attempt);
            try {
                caller.call(step.participant().name(),
                        () -> {
                            step.participant().forward(fwdKey, instance.context());
                            return null;
                        });
                rec.markForwardSucceeded();
                log.info("[SAGA] requestId={} forward step#{} '{}' SUCCEEDED", requestId, i, step.name());
            } catch (ParticipantException e) {
                rec.markForwardFailed(e.getMessage());
                failedAt = i;
                failure = e;
                log.warn("[SAGA] requestId={} forward step#{} '{}' FAILED kind={} reason={}",
                        requestId, i, step.name(), e.kind(), e.getMessage());
                break;
            }
        }

        if (failedAt == -1) {
            instance.markSucceeded();
            log.info("[SAGA] requestId={} FINAL status=SUCCEEDED (all {} step(s) effective)",
                    requestId, definition.steps().size());
            return;
        }

        compensate(instance, definition, failedAt, failure);
    }

    /**
     * 逆序补偿：从失败步的前一步开始；失败步本身若未生效则显式跳过。
     */
    private void compensate(SagaInstance instance, SagaDefinition definition,
                            int failedAt, ParticipantException failure) {
        String requestId = instance.requestId();
        SagaStep failedStep = definition.steps().get(failedAt);
        log.info("[SAGA] requestId={} rollback begin from step#{} (failed step '{}', kind={})",
                requestId, failedAt - 1, failedStep.name(), failure.kind());

        // 失败步及其后的所有步骤：正向从未生效，显式标记跳过，保证流水完整、无“漏补偿”歧义
        for (int j = failedAt; j < definition.steps().size(); j++) {
            instance.step(j).markCompensationSkipped();
            log.info("[SAGA] requestId={} forward step#{} '{}' never effective -> COMPENSATION_SKIPPED",
                    requestId, j, definition.steps().get(j).name());
        }

        for (int i = failedAt - 1; i >= 0; i--) {
            SagaStep step = definition.steps().get(i);
            StepRecord rec = instance.step(i);
            if (!rec.forwardTookEffect()) {
                rec.markCompensationSkipped();
                log.info("[SAGA] requestId={} compensate step#{} '{}' SKIPPED (never effective)",
                        requestId, i, step.name());
                continue;
            }
            String compKey = forwardKey(requestId, i); // 与正向同一业务键，补偿端独立去重
            int attempt = rec.recordCompensateAttempt();
            log.info("[SAGA] requestId={} compensate step#{} '{}' attempt={}",
                    requestId, i, step.name(), attempt);
            try {
                caller.callCompensation(step.participant().name(),
                        () -> {
                            step.participant().compensate(compKey, instance.context());
                            return null;
                        });
                rec.markCompensated();
                log.info("[SAGA] requestId={} compensate step#{} '{}' COMPENSATED",
                        requestId, i, step.name());
            } catch (ParticipantException e) {
                // 补偿失败：停在缺口处并给出可解释的终结态（生产中通常交由异步重试/人工介入）
                instance.markCompensationFailed(failedStep.name(), failure.getMessage(),
                        "compensate step '" + step.name() + "' failed: " + e.getMessage());
                log.error("[SAGA] requestId={} FINAL status=COMPENSATION_FAILED at step#{} '{}' "
                                + "(forward failure kind={} reason={})",
                        requestId, i, step.name(), failure.kind(), failure.getMessage());
                return;
            }
        }

        boolean noneMissing = instance.allCompensated();
        instance.markCompensated(failedStep.name(),
                failure.kind() + ": " + failure.getMessage());
        log.info("[SAGA] requestId={} FINAL status=COMPENSATED forwardFailure(kind={}, step='{}', reason={}) "
                        + "allCompensated={}",
                requestId, failure.kind(), failedStep.name(), failure.getMessage(), noneMissing);
    }

    /** 每步正向动作的幂等键：requestId + 步骤序号，天然防重放且与补偿配对。 */
    private static String forwardKey(String requestId, int stepIndex) {
        return requestId + ":step-" + stepIndex;
    }

    /** 供测试/上层判断失败分类。 */
    public static FailureKind classify(ParticipantException e) {
        return e.kind();
    }
}
