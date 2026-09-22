package com.github.h3yow.sagaorchestratorjava.core;

/**
 * Saga 中的一个步骤：绑定一个本地模拟参与方，并携带正向动作与补偿动作的操作类型。
 *
 * @param name       步骤名称（流水与日志中使用）
 * @param participant 目标参与方
 * @param action     正向操作类型（参与方据此分派处理逻辑）
 * @param compensation 补偿操作类型
 */
public record SagaStep(String name,
                       ParticipantClient participant,
                       String action,
                       String compensation) {
}
