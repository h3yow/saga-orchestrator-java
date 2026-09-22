package com.github.h3yow.sagaorchestratorjava.saga;

import com.github.h3yow.sagaorchestratorjava.participant.Participant;

/**
 * 编排中的一步：绑定一个本地模拟参与方。
 */
public record SagaStep(String name, Participant participant) {
}
