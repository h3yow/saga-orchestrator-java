package com.github.h3yow.sagaorchestratorjava.web.dto;

/**
 * 提交编排请求的入参。
 *
 * @param requestId    业务请求标识（幂等键，必填）
 * @param businessType 业务类型。完全不携带该字段（null）时按历史契约默认 ORDER_PLACEMENT；
 *                     空串或纯空白会被拒绝（400），不会回退到默认流程；
 *                     非空时必须是已注册类型（大小写不敏感、忽略首尾空白）
 */
public record SubmitRequest(String requestId, String businessType) {
}
