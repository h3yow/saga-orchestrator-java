package com.github.h3yow.sagaorchestratorjava.web.dto;

/**
 * 提交编排请求的入参。
 *
 * @param requestId    业务请求标识（幂等键，必填）
 * @param businessType 业务类型（必填，必须是已注册类型：ORDER_PLACEMENT / ACCOUNT_OPENING；
 *                     未注册或空白一律 400 拒绝，不会静默套用默认流程）
 */
public record SubmitRequest(String requestId, String businessType) {
}
