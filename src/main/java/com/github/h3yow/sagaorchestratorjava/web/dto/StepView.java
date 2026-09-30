package com.github.h3yow.sagaorchestratorjava.web.dto;

/**
 * 单个步骤的状态视图。
 *
 * @param name          步骤名
 * @param status        步骤状态
 * @param failureType   最终失败分类（如 RETRIES_EXHAUSTED / BUSINESS / CIRCUIT_OPEN）
 * @param rootCauseType 最后一次尝试的底层分类：重试耗尽后区分 TIMEOUT（参与方自报）
 *                      与 HARD_TIMEOUT（编排侧掐断）
 * @param failureMessage 判定依据描述
 */
public record StepView(String name,
                       String status,
                       String failureType,
                       String rootCauseType,
                       String failureMessage) {
}
