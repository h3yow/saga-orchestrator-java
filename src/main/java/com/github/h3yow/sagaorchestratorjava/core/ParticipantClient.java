package com.github.h3yow.sagaorchestratorjava.core;

/**
 * 参与方客户端抽象。真实系统中这里是 RPC/HTTP 客户端；本工程由本地模拟实现。
 * 实现方必须保证：同一 requestId + operation 的调用天然幂等。
 */
public interface ParticipantClient {

    /** 参与方标识（日志与熔断隔离使用） */
    String name();

    /**
     * 执行一个操作（正向或补偿）。
     *
     * @param requestId 业务请求标识（幂等键的一部分）
     * @param operation 操作类型
     * @param direction EXECUTE / COMPENSATE
     */
    void invoke(String requestId, String operation, String direction);
}
