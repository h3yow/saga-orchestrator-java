package com.github.h3yow.sagaorchestratorjava.core;

import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内 Saga 仓储。
 *
 * <p>{@link #createIfAbsent} 基于 {@link ConcurrentHashMap#computeIfAbsent} 原子完成
 * “查不存在则建”，使同一 requestId 并发提交时只有一个实例胜出，其余调用拿到同一实例，
 * 从而在入口处完成去重。
 */
@Repository
public class InMemorySagaRepository implements SagaRepository {

    private final ConcurrentHashMap<String, SagaInstance> store = new ConcurrentHashMap<>();

    @Override
    public Stored createIfAbsent(String requestId, SagaDefinition definition) {
        boolean[] created = {false};
        SagaInstance instance = store.computeIfAbsent(requestId, id -> {
            created[0] = true;
            return new SagaInstance(id, definition);
        });
        return new Stored(instance, created[0]);
    }

    @Override
    public Optional<SagaInstance> find(String requestId) {
        return Optional.ofNullable(store.get(requestId));
    }

    @Override
    public void save(SagaInstance instance) {
        // 实例对象即被仓储持有的同一引用，状态在实例锁内原地更新，此处无需额外写回。
        store.put(instance.requestId(), instance);
    }

    /** 清空存储（测试隔离使用）。 */
    public void clear() {
        store.clear();
    }
}
