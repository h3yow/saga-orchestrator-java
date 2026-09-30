package com.github.h3yow.sagaorchestratorjava.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Saga 编排器：对一个已存在的 {@link SagaInstance} 执行正向推进与失败补偿。
 *
 * <p>核心协议：
 * <ul>
 *   <li>调用 {@link #drive} 必须获取实例监视器锁，保证同一请求的重复提交/并发驱动
 *       在 JVM 内串行化；终态实例直接返回（已终结的编排不会被再次执行）。</li>
 *   <li>正向按定义顺序逐步执行，每步经 {@link ResilientCaller} 施加超时/重试/熔断保护。
 *       成功的步骤才允许被补偿。</li>
 *   <li>任一步明确失败（业务失败/重试耗尽/熔断开启）后，按逆序对“已生效”步骤逐一补偿；
 *       补偿动作同样经弹性包装，且参与方自身幂等，因此补偿天然可安全重放。</li>
 *   <li>全部补偿成功 -> COMPENSATED；任一补偿失败 -> COMPENSATION_FAILED（终态，需人工介入，
 *       绝不能静默丢失补偿）。</li>
 * </ul>
 */
@Component
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    private final ResilientCaller resilientCaller;
    private final SagaRepository repository;

    public SagaOrchestrator(ResilientCaller resilientCaller, SagaRepository repository) {
        this.resilientCaller = resilientCaller;
        this.repository = repository;
    }

    /**
     * 驱动实例直到终态。可安全地被同一实例的多个线程并发调用：第二个线程会在锁上等待，
     * 拿到锁后看到终态立即返回。
     */
    public void drive(SagaInstance instance) {
        synchronized (instance) {
            if (instance.isTerminal()) {
                log.info("requestId={} 编排已终态({})，重放直接返回，不执行任何动作",
                        instance.requestId(), instance.status());
                return;
            }
            instance.transitionTo(SagaStatus.RUNNING);
            instance.appendEvent("SAGA_STARTED", null,
                    "开始正向执行，共" + instance.stepCount() + "步");
            log.info("requestId={} ===== 开始正向执行 =====", instance.requestId());

            int failedIndex = forward(instance);
            if (failedIndex < 0) {
                instance.transitionTo(SagaStatus.COMPLETED);
                instance.appendEvent("SAGA_COMPLETED", null, "全部步骤成功，事务生效");
                log.info("requestId={} ===== 全部成功，编排完成(COMPLETED) =====",
                        instance.requestId());
                repository.save(instance);
                return;
            }
            compensate(instance, failedIndex);
            repository.save(instance);
        }
    }

    /**
     * 正向执行。
     *
     * @return 失败步骤的下标；全部成功返回 -1
     */
    private int forward(SagaInstance instance) {
        var definition = instance.definition();
        for (int i = 0; i < definition.steps().size(); i++) {
            SagaStep step = definition.steps().get(i);
            StepState state = instance.stepStateAt(i);

            // 重放保护：已执行的步骤不重复调用
            if (state.hasExecuted()) {
                log.info("requestId={} 步骤[{}]已执行，跳过重放",
                        instance.requestId(), step.name());
                continue;
            }

            instance.appendEvent("STEP_EXECUTING", step.name(),
                    "调用参与方[" + step.participant().name() + "]操作[" + step.action() + "]");
            try {
                resilientCaller.callWithResilience(
                        step.participant(), instance.requestId(),
                        step.action(), com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.EXECUTE,
                        resilientCaller.defaultRetryPolicy());
                state.markExecuted();
                instance.appendEvent("STEP_EXECUTED", step.name(), "正向成功并已生效");
                log.info("requestId={} 步骤[{}]执行成功", instance.requestId(), step.name());
            } catch (ParticipantException e) {
                state.markFailed(e.failureType(), e.rootFailureType(), e.getMessage());
                instance.appendEvent("STEP_FAILED", step.name(),
                        "失败分类=" + e.failureType()
                                + "；底层分类=" + e.rootFailureType() + describeTimeoutSource(e)
                                + "；判定依据=" + e.getMessage());
                log.warn("requestId={} 步骤[{}]失败，失败分类={} 底层分类={}{} 原因={}，准备逆序补偿",
                        instance.requestId(), step.name(), e.failureType(), e.rootFailureType(),
                        describeTimeoutSource(e), e.getMessage());
                return i;
            }
        }
        return -1;
    }

    /**
     * 从失败点前一个步骤开始逆序补偿。失败步骤本身未生效，不需要补偿。
     */
    private void compensate(SagaInstance instance, int failedIndex) {
        instance.transitionTo(SagaStatus.COMPENSATING);
        instance.appendEvent("COMPENSATION_STARTED", null,
                "从第" + failedIndex + "步之前逆序补偿已生效步骤");
        log.info("requestId={} ----- 开始逆序补偿(失败点下标={}) -----",
                instance.requestId(), failedIndex);

        for (int i = failedIndex - 1; i >= 0; i--) {
            SagaStep step = instance.definition().steps().get(i);
            StepState state = instance.stepStateAt(i);

            if (state.status() == StepStatus.COMPENSATED) {
                log.info("requestId={} 步骤[{}]已补偿，跳过（补偿幂等）",
                        instance.requestId(), step.name());
                continue;
            }
            if (!state.hasExecuted()) {
                // 未生效，无需补偿
                continue;
            }

            instance.appendEvent("STEP_COMPENSATING", step.name(),
                    "调用参与方[" + step.participant().name() + "]补偿操作[" + step.compensation() + "]");
            try {
                resilientCaller.callWithResilience(
                        step.participant(), instance.requestId(),
                        step.compensation(), com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.COMPENSATE,
                        resilientCaller.defaultRetryPolicy());
                state.markCompensated();
                instance.appendEvent("STEP_COMPENSATED", step.name(), "补偿成功");
                log.info("requestId={} 步骤[{}]补偿成功", instance.requestId(), step.name());
            } catch (ParticipantException e) {
                state.markCompensationFailed(e.failureType(), e.rootFailureType(), e.getMessage());
                instance.appendEvent("STEP_COMPENSATION_FAILED", step.name(),
                        "补偿失败分类=" + e.failureType()
                                + "；底层分类=" + e.rootFailureType() + describeTimeoutSource(e)
                                + "；判定依据=" + e.getMessage()
                                + "；编排进入COMPENSATION_FAILED，需人工介入，禁止遗漏补偿");
                log.error("requestId={} 步骤[{}]补偿失败({}/{}): {} —— 进入COMPENSATION_FAILED终态",
                        instance.requestId(), step.name(), e.failureType(),
                        e.rootFailureType(), e.getMessage());
                instance.transitionTo(SagaStatus.COMPENSATION_FAILED);
                instance.appendEvent("SAGA_COMPENSATION_FAILED", null,
                        "存在补偿失败步骤，事务未完全回退，需人工介入");
                return;
            }
        }

        instance.transitionTo(SagaStatus.COMPENSATED);
        instance.appendEvent("SAGA_COMPENSATED", null, "所有已生效步骤均已逆序补偿，事务完全回退");
        log.info("requestId={} ----- 补偿完成，编排终态(COMPENSATED)，无部分生效 -----",
                instance.requestId());
    }

    /**
     * 超时来源的人类可读判定说明：
     * HARD_TIMEOUT = 编排侧达到时间上限主动掐断；TIMEOUT = 参与方自行判定并上报。
     */
    private static String describeTimeoutSource(ParticipantException e) {
        FailureType root = e.rootFailureType();
        if (root == FailureType.HARD_TIMEOUT) {
            return "（编排侧硬超时主动掐断）";
        }
        if (root == FailureType.TIMEOUT) {
            return "（参与方自报超时）";
        }
        return "";
    }
}
