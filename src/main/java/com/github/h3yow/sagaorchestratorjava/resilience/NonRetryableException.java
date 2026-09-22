package com.github.h3yow.sagaorchestratorjava.resilience;

/** 不可重试的业务失败：不重试，直接进入补偿。 */
public class NonRetryableException extends ParticipantException {
    public NonRetryableException(String participant, String message) {
        super(FailureKind.NON_RETRYABLE, participant, message);
    }
}
