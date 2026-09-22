package com.github.h3yow.sagaorchestratorjava.participant;

/**
 * 参与方侧的一条流水（骨架）：记录一次真正落地的副作用。
 */
public record LedgerEntry(String requestId,
                          String participant,
                          String operation,
                          String direction,
                          long timestampMs) {
}
