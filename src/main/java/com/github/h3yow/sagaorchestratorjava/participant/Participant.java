package com.github.h3yow.sagaorchestratorjava.participant;

import com.github.h3yow.sagaorchestratorjava.saga.SagaContext;

/**
 * 本地模拟下游参与方。forward/compensate 都必须以 idempotencyKey 幂等。
 */
public interface Participant {

    String name();

    void forward(String idempotencyKey, SagaContext context);

    void compensate(String idempotencyKey, SagaContext context);
}
