package com.github.h3yow.sagaorchestratorjava.web.dto;

/**
 * 提交编排请求的入参。
 *
 * @param requestId    业务请求标识（幂等键，必填）
 * @param businessType 业务类型。仅当字段完全不携带（null）时按历史契约默认 ORDER_PLACEMENT；
 *                     空串/纯空白/未注册类型一律拒绝（400）
 */
public record SubmitRequest(String requestId, String businessType) {
}
