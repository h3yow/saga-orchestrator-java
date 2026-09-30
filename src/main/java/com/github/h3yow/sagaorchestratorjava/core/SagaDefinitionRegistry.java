package com.github.h3yow.sagaorchestratorjava.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务流程注册表：按 businessType 选择流程定义。
 *
 * <p>所有 {@link SagaDefinitionFactory} 在启动时由 Spring 注入；重复注册同一业务类型直接失败，
 * 避免两个流程静默抢同一标识。{@link #resolve} 对未注册的业务类型明确抛出
 * {@link IllegalArgumentException}，不允许静默套用默认流程。
 */
@Component
public class SagaDefinitionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SagaDefinitionRegistry.class);

    private final Map<String, SagaDefinitionFactory> factories = new LinkedHashMap<>();

    public SagaDefinitionRegistry(List<SagaDefinitionFactory> factories) {
        for (SagaDefinitionFactory factory : factories) {
            SagaDefinitionFactory previous = this.factories.put(factory.businessType(), factory);
            if (previous != null) {
                throw new IllegalStateException(
                        "业务类型 " + factory.businessType() + " 被重复注册: "
                                + previous.getClass() + " 与 " + factory.getClass());
            }
            log.info("注册业务流程 businessType={} -> {}",
                    factory.businessType(), factory.getClass().getSimpleName());
        }
    }

    /** 是否存在已注册的业务类型（大小写不敏感）。 */
    public boolean supports(String businessType) {
        return businessType != null && lookup(businessType) != null;
    }

    /** 全部已注册业务类型（启动诊断使用）。 */
    public List<String> registeredTypes() {
        return List.copyOf(factories.keySet());
    }

    /**
     * 按业务类型取得流程定义。
     *
     * @throws IllegalArgumentException 业务类型未注册（含 null/空白）
     */
    public SagaDefinition resolve(String businessType) {
        SagaDefinitionFactory factory = lookup(businessType);
        if (factory == null) {
            throw new IllegalArgumentException(
                    "未注册的业务类型: " + businessType + "；已注册类型=" + registeredTypes());
        }
        return factory.create();
    }

    private SagaDefinitionFactory lookup(String businessType) {
        if (businessType == null || businessType.isBlank()) {
            return null;
        }
        synchronized (factories) {
            for (Map.Entry<String, SagaDefinitionFactory> entry : factories.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(businessType.trim())) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }
}
