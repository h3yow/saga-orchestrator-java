package com.github.h3yow.sagaorchestratorjava.core;

/**
 * 业务流程工厂：每种业务类型对应一个工厂，描述该业务的步骤与参与方组合。
 */
public interface SagaDefinitionFactory {

    /** 业务类型标识（提交时按此选择流程）。 */
    String businessType();

    /** 构造该业务的 Saga 定义。 */
    SagaDefinition create();
}
