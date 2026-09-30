package com.github.h3yow.sagaorchestratorjava.core;

/**
 * 下游调用失败的分类，用于区分可重试的瞬时故障与不可重试的业务失败。
 */
public enum FailureType {
    NONE,
    /** 不可重试的业务失败（如下游返回业务拒绝、余额不足） */
    BUSINESS,
    /** 参与方自报超时：参与方在约定时间内自行判定并上报（默认按瞬时故障处理，可重试） */
    TIMEOUT,
    /** 编排侧硬超时：下游长时间不返回，编排器达到时间上限后主动掐断等待（可重试的瞬时故障） */
    HARD_TIMEOUT,
    /** 故障注入内部标记：下游模拟长期挂起（只睡眠、不上报错误），等待编排侧硬超时中断，不作为最终分类透出 */
    HANG,
    /** 其他瞬时故障（连接抖动等，可重试） */
    TRANSIENT,
    /** 有界重试耗尽后仍失败 */
    RETRIES_EXHAUSTED,
    /** 熔断开启时快速失败 */
    CIRCUIT_OPEN
}
