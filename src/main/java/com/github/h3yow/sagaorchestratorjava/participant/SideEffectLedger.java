package com.github.h3yow.sagaorchestratorjava.participant;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 所有模拟参与方共享的副作用账本。
 *
 * <p>只有“真正落地”的正向/补偿动作才会记一条账；因幂等去重而跳过、因故障而未生效的调用
 * 不允许记账。测试通过统计账本条目断言“无重复、无丢失、不漏补偿”。
 */
@Component
public class SideEffectLedger {

    private final CopyOnWriteArrayList<LedgerEntry> entries = new CopyOnWriteArrayList<>();

    public void record(LedgerEntry entry) {
        entries.add(entry);
    }

    /** 全部流水（按发生顺序）。 */
    public List<LedgerEntry> entries() {
        return List.copyOf(entries);
    }

    /** 统计某请求在某参与方、某方向上的副作用次数（期望值恒为 0 或 1）。 */
    public long count(String requestId, String participant, String direction) {
        return entries.stream()
                .filter(e -> e.requestId().equals(requestId)
                        && e.participant().equals(participant)
                        && e.direction().equals(direction))
                .count();
    }

    /** 某请求在某参与方上是否存在指定方向的副作用。 */
    public boolean exists(String requestId, String participant, String direction) {
        return count(requestId, participant, direction) > 0;
    }

    public void clear() {
        entries.clear();
    }
}
