package com.github.h3yow.sagaorchestratorjava.core;

/**
 * 一种业务流程的 Saga 定义工厂：负责描述业务类型标识与对应步骤序列。
 *
 * <p>每种业务流程（如下单、开户）各有一个实现，由 {@link SagaDefinitionRegistry}
 * 统一注册；提交时按 businessType 精确选中对应流程，未注册的类型必须明确拒绝。
 */
public interface SagaFactory {

    /** 业务类型标识（提交请求中的 businessType 必须与之精确匹配，大小写不敏感）。 */
    String businessType();

    /** 构造该业务流程的 Saga 定义。 */
    SagaDefinition create();
}
