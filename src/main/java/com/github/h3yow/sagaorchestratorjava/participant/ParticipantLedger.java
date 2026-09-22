package com.github.h3yow.sagaorchestratorjava.participant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 参与方调用流水台账：
 * <ul>
 *   <li>按 idempotencyKey 去重：同一正向动作/补偿动作无论被重试、重放或并发触发多少次，
 *       只可能“生效”一次，其余调用被识别为重复且不产生副作用；</li>
 *   <li>同时按发生顺序保留生效流水，供测试断言“无重复、无丢失、补偿齐备”并打印日志。</li>
 * </ul>
 * 它模拟了真实下游参与方自身必须具备的幂等能力。
 */
@Component
public class ParticipantLedger {

    private static final Logger log = LoggerFactory.getLogger(ParticipantLedger.class);

    private final Set<String> forwardKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> compensateKeys = ConcurrentHashMap.newKeySet();
    private final List<String> forwardEntries = new CopyOnWriteArrayList<>();
    private final List<String> compensateEntries = new CopyOnWriteArrayList<>();

    private static String key(String participant, String idempotencyKey) {
        return participant + "#" + idempotencyKey;
    }

    /** 生效（非重复）的正向调用流水，按生效顺序排列。 */
    public List<String> forwardEntries() {
        return List.copyOf(forwardEntries);
    }

    /** 生效（非重复）的补偿调用流水，按生效顺序排列。 */
    public List<String> compensateEntries() {
        return List.copyOf(compensateEntries);
    }

    public boolean hasForward(String participant, String idempotencyKey) {
        return forwardKeys.contains(key(participant, idempotencyKey));
    }

    public boolean hasCompensation(String participant, String idempotencyKey) {
        return compensateKeys.contains(key(participant, idempotencyKey));
    }

    /**
     * 正向调用登记：key 首次出现时生效并记录流水，返回 true；重复调用返回 false。
     */
    public boolean recordForwardIfAbsent(String participant, String idempotencyKey) {
        String k = key(participant, idempotencyKey);
        if (forwardKeys.add(k)) {
            forwardEntries.add(k);
            log.info("[LEDGER] forward EFFECTIVE  -> {}", k);
            return true;
        }
        log.info("[LEDGER] forward DUPLICATE  -> {}", k);
        return false;
    }

    /**
     * 补偿调用登记：与正向独立计数，补偿自身幂等。
     */
    public boolean recordCompensationIfAbsent(String participant, String idempotencyKey) {
        String k = key(participant, idempotencyKey);
        if (compensateKeys.add(k)) {
            compensateEntries.add(k);
            log.info("[LEDGER] compensate EFFECTIVE -> {}", k);
            return true;
        }
        log.info("[LEDGER] compensate DUPLICATE -> {}", k);
        return false;
    }

    public int effectiveForwardCount() {
        return forwardEntries.size();
    }

    public int effectiveCompensateCount() {
        return compensateEntries.size();
    }

    public void clear() {
        forwardKeys.clear();
        compensateKeys.clear();
        forwardEntries.clear();
        compensateEntries.clear();
    }
}
