package com.github.h3yow.sagaorchestratorjava.participant;

import com.github.h3yow.sagaorchestratorjava.core.ParticipantClient;
import com.github.h3yow.sagaorchestratorjava.core.ParticipantException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模拟参与方基类。
 *
 * <p>在真实业务动作（{@link #doExecute} / {@link #doCompensate}，本工程以空操作为副作用）
 * 之外统一处理三件事：
 * <ol>
 *   <li><b>幂等去重</b>：以 (requestId, direction, operation) 为键记录“已生效”的调用。
 *       同一键的重复调用直接返回成功、不重复落地副作用（补偿同样幂等）。</li>
 *   <li><b>故障注入</b>：调用前先过 {@link FaultPlan}，命中规则则先睡眠再抛对应类型异常。
 *       故障调用永远不会写入“已生效”集合，因此不会记账，重放时安全。</li>
 *   <li><b>副作用记账</b>：只有真正生效的调用写入 {@link SideEffectLedger}，作为无重复/无丢失
 *       的断言依据，并打印参与方流水。</li>
 * </ol>
 */
public abstract class AbstractSimulatedParticipant implements ParticipantClient {

    /** 正向方向标识。 */
    public static final String EXECUTE = "EXECUTE";
    /** 补偿方向标识。 */
    public static final String COMPENSATE = "COMPENSATE";

    private static final Logger log = LoggerFactory.getLogger(AbstractSimulatedParticipant.class);

    private final String name;
    private final SideEffectLedger ledger;

    /** 已生效调用的幂等键集合。 */
    private final Set<String> appliedKeys = ConcurrentHashMap.newKeySet();
    /**
     * 参与方维度的调用计数（键含方向/操作，不含 requestId）：
     * 同一请求的多次重试、不同请求的多次调用都会累计，用于驱动故障计划与熔断。
     */
    private final Map<String, int[]> invocationCounters = new ConcurrentHashMap<>();

    /** 当前生效的故障计划，默认为无故障；测试可动态替换。 */
    private volatile FaultPlan faultPlan = FaultPlan.none();

    /**
     * 每次正常调用前的固定延迟（毫秒，&lt;=0 表示不延迟）。
     * 仅影响无故障调用，用于并发测试中制造“调用进行中”的时间窗口；不影响故障/挂起注入。
     */
    private volatile long callDelayMillis = 0L;

    protected AbstractSimulatedParticipant(String name, SideEffectLedger ledger) {
        this.name = name;
        this.ledger = ledger;
    }

    @Override
    public String name() {
        return name;
    }

    /** 设置/替换故障注入计划（测试使用）。 */
    public void setFaultPlan(FaultPlan faultPlan) {
        this.faultPlan = faultPlan == null ? FaultPlan.none() : faultPlan;
    }

    /** 设置每次正常调用前的固定延迟（测试使用）。 */
    public void setCallDelayMillis(long callDelayMillis) {
        this.callDelayMillis = Math.max(0L, callDelayMillis);
    }

    /** 清空幂等记录与调用计数（测试隔离使用）。 */
    public void reset() {
        appliedKeys.clear();
        invocationCounters.clear();
        callDelayMillis = 0L;
    }

    @Override
    public void invoke(String requestId, String operation, String direction) {
        String idempotencyKey = requestId + "|" + direction + "|" + operation;

        // 幂等：已生效的操作重放直接返回成功，不产生重复副作用
        if (appliedKeys.contains(idempotencyKey)) {
            log.info("[{}] 命中幂等，跳过重复生效 requestId={} {} operation={} 判定依据: 已生效键存在",
                    name, requestId, direction, operation);
            return;
        }

        int invocation = nextInvocation(direction, operation);

        // 故障注入优先：命中则不生效、不记账
        for (FaultPlan.Rule rule : faultPlan.rules()) {
            if (rule.matches(invocation, direction, operation)) {
                if (rule.hangForever()) {
                    // 模拟下游卡死：不返回、不报错、不产生任何副作用、不记账。
                    // 等待编排侧硬超时到达后中断本线程（cancel(true)）。
                    log.warn("[{}] 模拟下游永久挂起（不返回/不报错/不记账）requestId={} {} operation={} 第{}次调用，等待编排侧硬超时掐断",
                            name, requestId, direction, operation, invocation);
                    hangUntilInterrupted();
                    // 被中断后也不允许落到“生效”逻辑：以瞬时故障结束（编排侧通常已先按 ORCHESTRATOR_TIMEOUT 掐断）
                    Thread.currentThread().interrupt();
                    throw new ParticipantException(
                            "参与方[" + name + "] 挂起被硬超时中断，本次调用未生效",
                            com.github.h3yow.sagaorchestratorjava.core.FailureType.ORCHESTRATOR_TIMEOUT);
                }
                if (rule.sleepMillis() > 0) {
                    log.warn("[{}] 模拟下游阻塞 {}ms requestId={} {} operation={}",
                            name, rule.sleepMillis(), requestId, direction, operation);
                    sleep(rule.sleepMillis());
                }
                log.warn("[{}] 抛出故障({}) requestId={} {} operation={} 第{}次调用 判定依据: {}",
                        name, rule.type(), requestId, direction, operation, invocation, rule.message());
                throw new ParticipantException(
                        "参与方[" + name + "] " + rule.message(), rule.type());
            }
        }

        long delay = callDelayMillis;
        if (delay > 0) {
            log.info("[{}] 模拟下游处理耗时 {}ms requestId={} {} operation={}",
                    name, delay, requestId, direction, operation);
            sleep(delay);
        }

        // 真实业务动作（本地模拟）。动作与“登记生效”之间由键集合的原子插入保证只生效一次。
        if (EXECUTE.equals(direction)) {
            doExecute(requestId, operation);
        } else if (COMPENSATE.equals(direction)) {
            doCompensate(requestId, operation);
        } else {
            throw new IllegalArgumentException("未知调用方向: " + direction);
        }

        if (appliedKeys.add(idempotencyKey)) {
            ledger.record(new LedgerEntry(requestId, name, operation, direction,
                    System.currentTimeMillis()));
            log.info("[{}] 副作用落地 requestId={} {} operation={} 流水: 已记账(第{}次调用)",
                    name, requestId, direction, operation, invocation);
        } else {
            log.info("[{}] 并发重复调用被幂等拦截 requestId={} {} operation={}",
                    name, requestId, direction, operation);
        }
    }

    /** 正向业务动作（本地模拟，默认无操作即成功；子类可覆盖加入状态变更）。 */
    protected void doExecute(String requestId, String operation) {
        // no-op：本地模拟下游副作用，真正的“生效”以记账为准
    }

    /** 补偿业务动作（本地模拟，默认无操作即成功；子类可覆盖）。 */
    protected void doCompensate(String requestId, String operation) {
        // no-op
    }

    private int nextInvocation(String direction, String operation) {
        int[] counter = invocationCounters.computeIfAbsent(direction + "|" + operation, k -> new int[1]);
        synchronized (counter) {
            return ++counter[0];
        }
    }

    /** 无限等待直到被中断（编排侧硬超时 cancel(true)）；用于模拟下游永久卡死。 */
    private static void hangUntilInterrupted() {
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            // 预期路径：被编排器硬超时中断，返回后由调用方按未生效处理
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParticipantException("模拟阻塞被中断",
                    com.github.h3yow.sagaorchestratorjava.core.FailureType.TRANSIENT, e);
        }
    }
}
