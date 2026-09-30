package com.github.h3yow.sagaorchestratorjava.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务流程注册表：收集所有 {@link SagaFactory}，按 businessType 精确解析流程定义。
 *
 * <p>语义约定：
 * <ul>
 *   <li>注册过的业务类型返回对应工厂创建的定义；</li>
 *   <li>未注册 / 空白的业务类型直接抛 {@link IllegalArgumentException} 明确拒绝，
 *       绝不静默套用默认流程。</li>
 * </ul>
 * 不同业务流程可并发运行，各自的正向执行、逆序补偿与幂等去重均按 requestId 独立成立。
 */
@Component
public class SagaDefinitionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SagaDefinitionRegistry.class);

    private final Map<String, SagaFactory> factories = new LinkedHashMap<>();

    public SagaDefinitionRegistry(List<SagaFactory> factoryList) {
        for (SagaFactory factory : factoryList) {
            register(factory);
        }
        log.info("已注册业务流程: {}", factories.keySet());
    }

    private void register(SagaFactory factory) {
        String key = normalize(factory.businessType());
        if (factories.put(key, factory) != null) {
            throw new IllegalStateException("业务类型重复注册: " + factory.businessType());
        }
    }

    /**
     * 按业务类型解析 Saga 定义。
     *
     * @throws IllegalArgumentException 业务类型为空或未注册
     */
    public SagaDefinition resolve(String businessType) {
        if (businessType == null || businessType.isBlank()) {
            throw new IllegalArgumentException(
                    "业务类型不能为空，已注册的业务类型: " + registeredTypes());
        }
        SagaFactory factory = factories.get(normalize(businessType));
        if (factory == null) {
            throw new IllegalArgumentException(
                    "未注册的业务类型: " + businessType + "，已注册的业务类型: " + registeredTypes());
        }
        return factory.create();
    }

    /** 已注册业务类型（诊断/错误信息使用）。 */
    public List<String> registeredTypes() {
        return List.copyOf(factories.keySet());
    }

    private static String normalize(String businessType) {
        return businessType.trim().toUpperCase();
    }
}
