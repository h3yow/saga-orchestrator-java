package com.github.h3yow.sagaorchestratorjava.core;

/**
 * 参与方调用异常，携带失败分类，供编排层决定重试/补偿/快速失败。
 */
public class ParticipantException extends RuntimeException {

    private final FailureType failureType;

    public ParticipantException(String message, FailureType failureType) {
        super(message);
        this.failureType = failureType;
    }

    public ParticipantException(String message, FailureType failureType, Throwable cause) {
        super(message, cause);
        this.failureType = failureType;
    }

    public FailureType failureType() {
        return failureType;
    }
}
