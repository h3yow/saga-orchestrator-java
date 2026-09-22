package com.github.h3yow.sagaorchestratorjava.saga;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内编排实例存储。
 * <ul>
 *   <li>以 requestId 幂等去重：getOrCreate 在同一把 per-key 锁下完成判定与创建，
 *       并发/重复提交只会得到同一个实例，编排只会被执行一次；</li>
 *   <li>实例字段均为 volatile/原子计数，{@link #find} 在任意时刻读到的状态都是
 *       单调推进中的一致快照，已终结后永远读到终结态（支持重放查询）。</li>
 * </ul>
 */
@Component
public class SagaRepository {

    private final ConcurrentHashMap<String, SagaInstance> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    /** 返回 requestId 对应的锁；编排执行全程持有，串行化同请求的并发提交。 */
    public Object lockFor(String requestId) {
        return locks.computeIfAbsent(requestId, k -> new Object());
    }

    public Optional<SagaInstance> find(String requestId) {
        return Optional.ofNullable(store.get(requestId));
    }

    /** 清空全部实例与锁（仅用于测试隔离；生产运行期不调用）。 */
    public void reset() {
        store.clear();
        locks.clear();
    }

    /**
     * 已存在则返回既有实例（重放，prototype 不会被使用）；否则原子放入并初始化流水。
     * 调用方必须已持有 {@link #lockFor(String)} 对应的锁。
     */
    public SagaInstance getOrCreate(String requestId, SagaDefinition definition, SagaContext context) {
        SagaInstance existing = store.get(requestId);
        if (existing != null) {
            return existing;
        }
        SagaInstance created = new SagaInstance(requestId, definition.type(), context, definition.steps().size());
        created.initSteps(definition.steps().stream().map(SagaStep::name).toList());
        store.put(requestId, created);
        return created;
    }
}
