package com.github.h3yow.sagaorchestratorjava.participant;

import com.github.h3yow.sagaorchestratorjava.core.FailureType;

import java.util.ArrayList;
import java.util.List;

/**
 * 参与方故障注入计划。
 *
 * <p>每条 {@link Rule} 表示：当（请求标识、方向、操作类型）匹配，且这是该参与方在当前
 * （方向+操作）维度上的第 {@code invocation} 次（落在 [from, to] 区间，to=-1 表示无界）调用时，
 * 触发指定故障。用于模拟：
 * <ul>
 *   <li>第 1 次调用超时、第 2 次成功（可重试瞬时故障，验证重试）；</li>
 *   <li>直接 BUSINESS 失败（不可重试，验证立即回滚）；</li>
 *   <li>从某次起持续失败（验证重试耗尽与熔断开启）；</li>
 *   <li>长期不返回（{@link #hangAt}，验证编排侧硬超时主动掐断）；</li>
 *   <li>只对指定 requestId 生效（混合并发下，让部分请求卡死、其余请求保持健康）。</li>
 * </ul>
 * 规则按加入顺序匹配，取第一条命中的规则。
 */
public class FaultPlan {

    /** 匹配任意操作类型。 */
    public static final String ANY_OPERATION = "*";
    /** 匹配任意方向。 */
    public static final String ANY_DIRECTION = "*";
    /** 匹配任意请求标识。 */
    public static final String ANY_REQUEST = "*";

    /**
     * @param invocationFrom 起始匹配调用次数（含）
     * @param invocationTo   结束匹配调用次数（含），-1 表示无界
     * @param requestId      仅对该请求标识生效；{@link #ANY_REQUEST} 表示任意请求
     */
    public record Rule(int invocationFrom, int invocationTo, String requestId,
                       String direction, String operation,
                       FailureType type, String message, long sleepMillis) {

        public boolean matches(int invocation, String reqId, String dir, String op) {
            boolean inRange = invocation >= invocationFrom
                    && (invocationTo < 0 || invocation <= invocationTo);
            return inRange
                    && (ANY_REQUEST.equals(requestId) || requestId.equals(reqId))
                    && (ANY_DIRECTION.equals(direction) || direction.equals(dir))
                    && (ANY_OPERATION.equals(operation) || operation.equals(op));
        }
    }

    private final List<Rule> rules = new ArrayList<>();

    public static FaultPlan none() {
        return new FaultPlan();
    }

    public List<Rule> rules() {
        return List.copyOf(rules);
    }

    /** 第 invocation 次匹配方向的调用失败（任意操作）。 */
    public FaultPlan failAt(int invocation, String direction, FailureType type, String message) {
        rules.add(new Rule(invocation, invocation, ANY_REQUEST, direction, ANY_OPERATION,
                type, message, 0L));
        return this;
    }

    /** 第 invocation 次匹配方向与操作的调用失败。 */
    public FaultPlan failAt(int invocation, String direction, String operation,
                            FailureType type, String message) {
        rules.add(new Rule(invocation, invocation, ANY_REQUEST, direction, operation,
                type, message, 0L));
        return this;
    }

    /** 第 invocation 次匹配调用先睡眠 sleepMillis 再抛 TIMEOUT，模拟下游超时。 */
    public FaultPlan timeoutAt(int invocation, String direction, String operation, long sleepMillis) {
        rules.add(new Rule(invocation, invocation, ANY_REQUEST, direction, operation,
                FailureType.TIMEOUT, "模拟下游超时(" + sleepMillis + "ms)", sleepMillis));
        return this;
    }

    /**
     * 仅对指定 requestId，从第 fromInvocation 次匹配调用起长期挂起。
     * 用于混合并发：只让特定请求卡住，其余请求不受影响。
     */
    public FaultPlan hangFromForRequest(String requestId, int fromInvocation,
                                        String direction, String operation, long hangMillis) {
        rules.add(new Rule(fromInvocation, -1, requestId, direction, operation,
                FailureType.HANG, "模拟指定请求长期不返回(" + hangMillis + "ms/次)", hangMillis));
        return this;
    }

    /**
     * 第 invocation 次匹配调用起长期挂起（睡眠 hangMillis），既不返回成功也不上报错误，
     * 用于模拟“下游卡住”，等待编排侧硬超时主动掐断（中断）。
     * 与 {@link #timeoutAt} 的区别：本规则不会自行上抛 TIMEOUT，只能由编排器中断终止。
     */
    public FaultPlan hangAt(int invocation, String direction, String operation, long hangMillis) {
        rules.add(new Rule(invocation, invocation, ANY_REQUEST, direction, operation,
                FailureType.HANG, "模拟下游长期不返回(" + hangMillis + "ms)，等待编排侧硬超时掐断",
                hangMillis));
        return this;
    }

    /** 从第 fromInvocation 次起持续挂起（用于模拟下游持续卡死，重试全部被编排侧掐断）。 */
    public FaultPlan hangFrom(int fromInvocation, String direction, String operation, long hangMillis) {
        rules.add(new Rule(fromInvocation, -1, ANY_REQUEST, direction, operation,
                FailureType.HANG, "模拟下游持续不返回(" + hangMillis + "ms/次)", hangMillis));
        return this;
    }

    /** 从第 fromInvocation 次起持续失败（用于打满熔断阈值）。 */
    public FaultPlan failFrom(int fromInvocation, String direction, FailureType type, String message) {
        rules.add(new Rule(fromInvocation, -1, ANY_REQUEST, direction, ANY_OPERATION,
                type, message, 0L));
        return this;
    }
}
