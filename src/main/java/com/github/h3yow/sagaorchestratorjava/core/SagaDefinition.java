package com.github.h3yow.sagaorchestratorjava.core;

import java.util.List;

/**
 * 一次业务操作对应的 Saga 定义：按顺序执行的多个步骤。
 *
 * @param type  业务类型
 * @param steps 有序步骤列表（按定义顺序正向执行，按逆序补偿）
 */
public record SagaDefinition(String type, List<SagaStep> steps) {
}
