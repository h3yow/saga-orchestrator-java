package com.github.h3yow.sagaorchestratorjava.saga;

import java.util.List;

/**
 * 一次业务操作的步骤序列，按顺序正向执行、按逆序补偿。
 */
public record SagaDefinition(String type, List<SagaStep> steps) {
}
