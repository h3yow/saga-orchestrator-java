package com.github.h3yow.sagaorchestratorjava.resilience;

/** 可重试的瞬时故障（超时、瞬时错误）。 */
public class RetryableException extends ParticipantException {
    public RetryableException(String participant, String message) {
        super(FailureKind.RETRYABLE, participant, message);
    }

    public RetryableException(String participant, String message, Throwable cause) {
        super(FailureKind.RETRYABLE, participant, message, cause);
    }
}
