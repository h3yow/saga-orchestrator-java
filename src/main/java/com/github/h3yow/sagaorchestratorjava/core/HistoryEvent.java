package com.github.h3yow.sagaorchestratorjava.core;

/**
 * 编排流水事件：记录每一次执行/补偿/判定，保证状态推进可解释。
 *
 * @param seq         单调递增序号
 * @param timestampMs 事件时间
 * @param requestId   请求标识
 * @param eventType   事件类型
 * @param stepName    关联步骤（可为空）
 * @param detail      判定依据等详情
 */
public record HistoryEvent(long seq,
                           long timestampMs,
                           String requestId,
                           String eventType,
                           String stepName,
                           String detail) {
}
