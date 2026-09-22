package com.github.h3yow.sagaorchestratorjava.web.dto;

/**
 * 提交编排请求的入参。
 *
 * @param requestId    业务请求标识（幂等键，必填）
 * @param businessType 业务类型（可空，默认 ORDER_PLACEMENT）
 */
public record SubmitRequest(String requestId, String businessType) {
}
