package com.github.h3yow.sagaorchestratorjava.web.dto;

/** 单个步骤的状态视图。 */
public record StepView(String name, String status, String failureType, String failureMessage) {
}
