package com.github.h3yow.sagaorchestratorjava.core;

/**
 * 参与方调用异常，携带失败分类，供编排层决定重试/补偿/快速失败。
 */
public class ParticipantException extends RuntimeException {

    private final FailureType failureType;
    /**
     * 底层失败分类：当本异常为包装后的最终分类（如 RETRIES_EXHAUSTED）时，
     * 保留最后一次尝试的真实分类（TIMEOUT / HARD_TIMEOUT / TRANSIENT），便于结果与日志区分。
     */
    private final FailureType rootFailureType;

    public ParticipantException(String message, FailureType failureType) {
        super(message);
        this.failureType = failureType;
        this.rootFailureType = failureType;
    }

    public ParticipantException(String message, FailureType failureType, Throwable cause) {
        super(message, cause);
        this.failureType = failureType;
        this.rootFailureType = failureType;
    }

    public ParticipantException(String message, FailureType failureType, FailureType rootFailureType) {
        super(message);
        this.failureType = failureType;
        this.rootFailureType = rootFailureType == null ? failureType : rootFailureType;
    }

    public FailureType failureType() {
        return failureType;
    }

    /** 底层失败分类（未被包装时与 {@link #failureType()} 相同）。 */
    public FailureType rootFailureType() {
        return rootFailureType;
    }
}
