package com.github.h3yow.sagaorchestratorjava.mock;

/**
 * 单个模拟参与方的行为剧本。
 *
 * @param mode           行为模式
 * @param transientFailures FLAKY 模式下，前多少次调用失败（之后自动恢复成功）；
 *                       ALWAYS_FAIL 模式下该值无意义
 * @param slowMs         SLOW 模式下调用耗时（应大于配置的超时阈值，以触发超时）
 * @param businessMessage BUSINESS_FAIL 模式下的业务失败原因（不可重试）
 */
public record MockScenario(Mode mode, int transientFailures, long slowMs, String businessMessage) {

    public enum Mode {
        /** 始终成功 */
        OK,
        /** 调用缓慢，触发超时（可重试） */
        SLOW,
        /** 前 transientFailures 次调用抛瞬时错误，随后恢复成功 */
        FLAKY,
        /** 持续抛瞬时错误（可重试），用于回滚与熔断场景 */
        ALWAYS_FAIL,
        /** 业务失败（不可重试），立即终止正向执行 */
        BUSINESS_FAIL
    }

    public static MockScenario ok() {
        return new MockScenario(Mode.OK, 0, 0, null);
    }

    public static MockScenario slow(long slowMs) {
        return new MockScenario(Mode.SLOW, 0, slowMs, null);
    }

    public static MockScenario flaky(int transientFailures) {
        return new MockScenario(Mode.FLAKY, transientFailures, 0, null);
    }

    public static MockScenario alwaysFail() {
        return new MockScenario(Mode.ALWAYS_FAIL, 0, 0, null);
    }

    public static MockScenario businessFail(String message) {
        return new MockScenario(Mode.BUSINESS_FAIL, 0, 0, message);
    }
}
