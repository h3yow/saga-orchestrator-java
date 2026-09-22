package com.github.h3yow.sagaorchestratorjava.saga;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单步执行记录（执行/补偿流水）。
 * 状态单调推进：PENDING -> SUCCEEDED/FAILED -> COMPENSATED / COMPENSATION_SKIPPED。
 * 所有计数均为原子操作，保证并发与重试下流水仍然准确、可解释。
 */
public class StepRecord {

    private final int index;
    private final String name;
    private final AtomicInteger forwardAttempts = new AtomicInteger();
    private final AtomicInteger compensateAttempts = new AtomicInteger();
    private volatile StepStatus status = StepStatus.PENDING;
    private volatile String forwardFailureReason;
    private volatile String compensateFailureReason;

    public StepRecord(int index, String name) {
        this.index = index;
        this.name = name;
    }

    public int index() {
        return index;
    }

    public String name() {
        return name;
    }

    public StepStatus status() {
        return status;
    }

    public int forwardAttempts() {
        return forwardAttempts.get();
    }

    public int compensateAttempts() {
        return compensateAttempts.get();
    }

    public String failureReason() {
        return forwardFailureReason != null ? forwardFailureReason : compensateFailureReason;
    }

    public String forwardFailureReason() {
        return forwardFailureReason;
    }

    public String compensateFailureReason() {
        return compensateFailureReason;
    }

    /** 记录一次正向调用尝试（含首次与重试）。 */
    public int recordForwardAttempt() {
        return forwardAttempts.incrementAndGet();
    }

    /** 记录一次补偿调用尝试。 */
    public int recordCompensateAttempt() {
        return compensateAttempts.incrementAndGet();
    }

    public void markForwardSucceeded() {
        this.status = StepStatus.SUCCEEDED;
    }

    public void markForwardFailed(String reason) {
        this.status = StepStatus.FAILED;
        this.forwardFailureReason = reason;
    }

    public void markCompensated() {
        this.status = StepStatus.COMPENSATED;
    }

    /** 该步从未正向生效，无需补偿（逆序遍历时显式标记，保证流水可解释）。 */
    public void markCompensationSkipped() {
        this.status = StepStatus.COMPENSATION_SKIPPED;
    }

    /** 正向是否已经生效：只有生效过的步骤才允许/需要补偿。 */
    public boolean forwardTookEffect() {
        return status == StepStatus.SUCCEEDED || status == StepStatus.COMPENSATED;
    }

    public boolean isCompensated() {
        return status == StepStatus.COMPENSATED || status == StepStatus.COMPENSATION_SKIPPED;
    }
}
