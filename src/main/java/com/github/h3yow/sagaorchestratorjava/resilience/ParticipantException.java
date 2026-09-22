package com.github.h3yow.sagaorchestratorjava.resilience;

/**
 * 下游调用失败，携带可重试/不可重试分类。
 */
public class ParticipantException extends RuntimeException {

    private final FailureKind kind;

    public ParticipantException(FailureKind kind, String participant, String message) {
        super(participant + ": " + message);
        this.kind = kind;
    }

    public ParticipantException(FailureKind kind, String participant, String message, Throwable cause) {
        super(participant + ": " + message, cause);
        this.kind = kind;
    }

    public FailureKind kind() {
        return kind;
    }
}
