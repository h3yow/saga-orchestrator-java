package com.github.h3yow.sagaorchestratorjava.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个请求对应的 Saga 实例：持有定义、步骤状态、生命周期状态与完整流水。
 *
 * <p>状态机（只允许单调推进）：
 * <pre>
 *   PENDING -> RUNNING -> COMPLETED
 *                     \-> COMPENSATING -> COMPENSATED
 *                                      \-> COMPENSATION_FAILED
 * </pre>
 * 三个 COMPLETED / COMPENSATED / COMPENSATION_FAILED 为终态，终态不可再次执行。
 *
 * <p>所有变更均要求调用方在 {@code synchronized(instance)} 下进行（编排器驱动时持锁），
 * 配合仓储的原子创建，保证重复提交与并发驱动不会产生重复副作用。
 */
public class SagaInstance {

    private static final Set<SagaStatus> TERMINAL =
            Set.of(SagaStatus.COMPLETED, SagaStatus.COMPENSATED, SagaStatus.COMPENSATION_FAILED);

    private final String requestId;
    private final SagaDefinition definition;
    private final List<StepState> steps;
    private final Map<String, StepState> stepByName;

    private SagaStatus status = SagaStatus.PENDING;
    private final List<HistoryEvent> history = new ArrayList<>();
    private long sequence = 0;

    public SagaInstance(String requestId, SagaDefinition definition) {
        this.requestId = requestId;
        this.definition = definition;
        this.steps = new ArrayList<>(definition.steps().size());
        this.stepByName = new LinkedHashMap<>();
        for (SagaStep step : definition.steps()) {
            StepState state = new StepState(step.name());
            steps.add(state);
            stepByName.put(step.name(), state);
        }
    }

    public String requestId() {
        return requestId;
    }

    public SagaDefinition definition() {
        return definition;
    }

    public SagaStatus status() {
        return status;
    }

    public boolean isTerminal() {
        return TERMINAL.contains(status);
    }

    /** 按定义顺序排列的步骤状态。 */
    public List<StepState> steps() {
        return List.copyOf(steps);
    }

    public StepState stepState(String stepName) {
        return stepByName.get(stepName);
    }

    public StepState stepStateAt(int index) {
        return steps.get(index);
    }

    public int stepCount() {
        return steps.size();
    }

    /** 追加一条流水，序号单调递增。须在实例锁内调用。 */
    public HistoryEvent appendEvent(String eventType, String stepName, String detail) {
        HistoryEvent event = new HistoryEvent(
                ++sequence, System.currentTimeMillis(), requestId, eventType, stepName, detail);
        history.add(event);
        return event;
    }

    /** 流水快照（查询时使用）。 */
    public List<HistoryEvent> history() {
        synchronized (this) {
            return List.copyOf(history);
        }
    }

    /**
     * 单调状态迁移。非法迁移抛异常；目标与当前相同则忽略（重放幂等）；
     * 终态只允许保持，不允许再迁移到其它状态。
     */
    public void transitionTo(SagaStatus target) {
        if (target == status) {
            return;
        }
        if (TERMINAL.contains(status)) {
            throw new IllegalStateException(
                    "Saga " + requestId + " 已处于终态 " + status + "，不能迁移到 " + target);
        }
        if (!allowed(status, target)) {
            throw new IllegalStateException(
                    "Saga " + requestId + " 非法状态迁移: " + status + " -> " + target);
        }
        status = target;
    }

    private static boolean allowed(SagaStatus from, SagaStatus to) {
        return switch (from) {
            case PENDING -> to == SagaStatus.RUNNING;
            case RUNNING -> to == SagaStatus.COMPLETED || to == SagaStatus.COMPENSATING;
            case COMPENSATING -> to == SagaStatus.COMPENSATED || to == SagaStatus.COMPENSATION_FAILED;
            default -> false;
        };
    }
}
