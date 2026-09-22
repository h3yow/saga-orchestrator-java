package com.github.h3yow.sagaorchestratorjava.core;

import java.util.Optional;

/**
 * Saga 实例存储抽象。createIfAbsent 必须是原子的，用于并发去重。
 */
public interface SagaRepository {

    /**
     * 原子地创建实例：若 requestId 已存在则返回已有实例，否则按定义新建并保存。
     */
    Stored createIfAbsent(String requestId, SagaDefinition definition);

    Optional<SagaInstance> find(String requestId);

    /** 持久化实例的最新状态。 */
    void save(SagaInstance instance);

    /** createIfAbsent 的返回结果：区分新建与已存在。 */
    record Stored(SagaInstance instance, boolean created) {
    }
}
