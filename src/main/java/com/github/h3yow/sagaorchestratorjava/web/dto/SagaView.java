package com.github.h3yow.sagaorchestratorjava.web.dto;

import java.util.List;

/**
 * 编排实例的查询视图：单调状态、每步状态、完整流水。
 */
public record SagaView(String requestId,
                       String status,
                       List<StepView> steps,
                       List<String> history,
                       boolean duplicated) {
}
