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
 *   <li>先按业务类型解析流程定义：空串/纯空白/未注册类型在建实例之前直接拒绝；</li>
 *   <li>仓储 {@code createIfAbsent} 原子去重：同一 requestId 只有一个实例；</li>
 *   <li>重复提交时校验业务类型与首次受理一致，不一致直接拒绝（409），不追加流水；</li>
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
    private final SagaDefinitionRegistry registry;

    public SagaService(SagaRepository repository,
                       SagaOrchestrator orchestrator,
                       SagaDefinitionRegistry registry) {
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.registry = registry;
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
            // 同一 requestId 再次提交时，业务类型必须与首次受理的一致：
            // 否则说明调用方拿同一幂等键提交了另一笔业务，直接拒绝，
            // 既不追加流水也不驱动编排，保证“提交的是哪个类型，结果就是哪条流程”。
            String existingType = instance.definition().type();
            if (!existingType.equals(definition.type())) {
                log.warn("requestId={} 业务类型不一致：首次受理={}，本次提交={}，拒绝受理",
                        requestId, existingType, definition.type());
                throw new IllegalStateException(
                        "requestId=" + requestId + " 已绑定业务类型 " + existingType
                                + "，本次提交的业务类型 " + definition.type() + " 不一致，拒绝受理");
            }
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

    /**
     * 按业务类型选择流程：
     * <ul>
     *   <li>仅当请求<b>完全没有携带</b> {@code businessType}（{@code null}）时，
     *       按历史提交契约默认走下单流程 {@code ORDER_PLACEMENT}；</li>
     *   <li>空串或纯空白（{@code ""}、{@code "   "}）视为“带了字段但内容为空”，
     *       明确拒绝（400），绝不静默回退到默认流程；</li>
     *   <li>其余非空类型必须在注册表中存在（大小写不敏感、忽略首尾空白），
     *       未注册一律拒绝（400），绝不静默套用默认流程。</li>
     * </ul>
     * 以上任何拒绝都发生在建实例之前，不会留下实例、副作用或流水。
     */
    private SagaDefinition resolveDefinition(String businessType) {
        if (businessType == null) {
            return registry.resolve(OrderSagaFactory.BUSINESS_TYPE);
        }
        if (businessType.isBlank()) {
            log.warn("拒绝受理：businessType 为空串或纯空白（仅完全未携带该字段时才允许走默认流程）");
            throw new IllegalArgumentException(
                    "业务类型不能为空串或纯空白；如需默认下单流程请完全不要携带 businessType 字段");
        }
        if (!registry.supports(businessType)) {
            log.warn("拒绝受理：businessType={} 未注册，已注册类型={}",
                    businessType, registry.registeredTypes());
            throw new IllegalArgumentException(
                    "未注册的业务类型: " + businessType
                            + "；已注册类型=" + registry.registeredTypes());
        }
        return registry.resolve(businessType);
    }
}
