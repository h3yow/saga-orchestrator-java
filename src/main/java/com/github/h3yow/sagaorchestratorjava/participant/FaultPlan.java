package com.github.h3yow.sagaorchestratorjava.participant;

import com.github.h3yow.sagaorchestratorjava.core.FailureType;

import java.util.ArrayList;
import java.util.List;

/**
 * 参与方故障注入计划。
 *
 * <p>每条 {@link Rule} 表示：当（方向、操作类型）匹配，且这是该参与方在当前请求上
 * 匹配调用的第 {@code invocation} 次（落在 [from, to] 区间，to=-1 表示无界）时，
 * 抛出指定类型的故障。用于模拟：
 * <ul>
 *   <li>第 1 次调用超时、第 2 次成功（可重试瞬时故障，验证重试）；</li>
 *   <li>直接 BUSINESS 失败（不可重试，验证立即回滚）；</li>
 *   <li>从某次起持续失败（验证重试耗尽与熔断开启）；</li>
 *   <li>补偿动作失败（验证 COMPENSATION_FAILED 终态）。</li>
 * </ul>
 * 规则按加入顺序匹配，取第一条命中的规则。
 */
public class FaultPlan {

    /** 匹配任意操作类型。 */
    public static final String ANY_OPERATION = "*";
    /** 匹配任意方向。 */
    public static final String ANY_DIRECTION = "*";

    /**
     * @param invocationFrom 起始匹配调用次数（含）
     * @param invocationTo   结束匹配调用次数（含），-1 表示无界
     */
    public record Rule(int invocationFrom, int invocationTo, String direction, String operation,
                       FailureType type, String message, long sleepMillis) {

        public boolean matches(int invocation, String dir, String op) {
            boolean inRange = invocation >= invocationFrom
                    && (invocationTo < 0 || invocation <= invocationTo);
            return inRange
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
        rules.add(new Rule(invocation, invocation, direction, ANY_OPERATION, type, message, 0L));
        return this;
    }

    /** 第 invocation 次匹配方向与操作的调用失败。 */
    public FaultPlan failAt(int invocation, String direction, String operation,
                            FailureType type, String message) {
        rules.add(new Rule(invocation, invocation, direction, operation, type, message, 0L));
        return this;
    }

    /** 第 invocation 次匹配调用先睡眠 sleepMillis 再抛 TIMEOUT，模拟下游超时。 */
    public FaultPlan timeoutAt(int invocation, String direction, String operation, long sleepMillis) {
        rules.add(new Rule(invocation, invocation, direction, operation,
                FailureType.TIMEOUT, "模拟下游超时(" + sleepMillis + "ms)", sleepMillis));
        return this;
    }

    /** 从第 fromInvocation 次起持续失败（用于打满熔断阈值）。 */
    public FaultPlan failFrom(int fromInvocation, String direction, FailureType type, String message) {
        rules.add(new Rule(fromInvocation, -1, direction, ANY_OPERATION, type, message, 0L));
        return this;
    }
}
