package com.github.h3yow.sagaorchestratorjava.resilience;

/** 熔断开启期间快速失败。 */
public class CircuitBreakerOpenException extends ParticipantException {
    public CircuitBreakerOpenException(String participant) {
        super(FailureKind.RETRYABLE, participant, "circuit breaker is OPEN");
    }
}
