package com.github.h3yow.sagaorchestratorjava.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 对外的编排服务。
 *
 * <p>提交语义（保证幂等）：
 * <ol>
 *   <li>仓储 {@code createIfAbsent} 原子去重：同一 requestId 只有一个实例；</li>
 *   <li>随后对实例执行 {@link SagaOrchestrator#drive}，编排器在实例锁内再次校验终态。</li>
 * </ol>
 * 因此重复提交、超时重试（客户端再次提交同一 requestId）、并发到达三种情况下，
 * 业务动作都只会执行一次；后到者要么等待首个执行完成并直接拿到终态，要么看到已终态立即返回。
 *
 * <p>查询语义：按 requestId 返回同一实例的单调状态与流水，终态之后永远不变。
 */
@Service
public class SagaService {

    private static final Logger log = LoggerFactory.getLogger(SagaService.class);

    private final SagaRepository repository;
    private final SagaOrchestrator orchestrator;
    private final OrderSagaFactory orderSagaFactory;

    public SagaService(SagaRepository repository,
                       SagaOrchestrator orchestrator,
                       OrderSagaFactory orderSagaFactory) {
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.orderSagaFactory = orderSagaFactory;
    }

    /**
     * 提交一个业务请求并驱动到终态。
     *
     * @return 该 requestId 对应的唯一实例（终态可安全反复查询）
     */
    public SagaInstance submit(String requestId, String businessType) {
        SagaDefinition definition = resolveDefinition(businessType);
        SagaRepository.Stored stored = repository.createIfAbsent(requestId, definition);
        SagaInstance instance = stored.instance();

        if (!stored.created()) {
            log.info("requestId={} 重复提交，命中已有实例(当前状态={})，不新建编排",
                    requestId, instance.status());
            instance.appendEvent("SUBMIT_DEDUPED", null,
                    "重复提交被幂等拦截，复用已有实例（未产生新副作用）");
        } else {
            log.info("requestId={} 首次受理(businessType={})", requestId, businessType);
            instance.appendEvent("SUBMIT_ACCEPTED", null,
                    "首次受理 businessType=" + businessType);
        }

        // 无论新建还是重复，都进入驱动；编排器保证终态不重放、并发串行化
        orchestrator.drive(instance);
        return instance;
    }

    public Optional<SagaInstance> query(String requestId) {
        return repository.find(requestId);
    }

    private SagaDefinition resolveDefinition(String businessType) {
        if (businessType == null
                || OrderSagaFactory.BUSINESS_TYPE.equalsIgnoreCase(businessType)) {
            return orderSagaFactory.create();
        }
        throw new IllegalArgumentException("未知业务类型: " + businessType);
    }
}
