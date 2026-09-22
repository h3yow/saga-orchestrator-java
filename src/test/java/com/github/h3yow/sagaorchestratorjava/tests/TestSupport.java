package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.core.HistoryEvent;
import com.github.h3yow.sagaorchestratorjava.core.InMemorySagaRepository;
import com.github.h3yow.sagaorchestratorjava.core.ResilientCaller;
import com.github.h3yow.sagaorchestratorjava.core.SagaInstance;
import com.github.h3yow.sagaorchestratorjava.participant.*;
import org.junit.jupiter.api.Assertions;

import java.util.UUID;

/**
 * 测试公共工具：用例隔离重置、请求标识生成、以及“请求标识 + 执行/补偿流水 + 判定依据”的统一打印。
 */
final class TestSupport {

    private TestSupport() {
    }

    static String newRequestId(String scenario) {
        return scenario + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 每个用例开始前调用：清空仓储、账本、幂等记录、熔断器，保证互不干扰。 */
    static void resetAll(InMemorySagaRepository repository,
                         SideEffectLedger ledger,
                         ResilientCaller caller,
                         AbstractSimulatedParticipant... participants) {
        repository.clear();
        ledger.clear();
        caller.reset();
        for (AbstractSimulatedParticipant p : participants) {
            p.reset();
            p.setFaultPlan(FaultPlan.none());
        }
    }

    /** 打印请求标识、编排终态、执行/补偿流水（参与方账本 + 编排历史），作为判定依据留痕。 */
    static void printReport(String title,
                            String requestId,
                            SagaInstance instance,
                            SideEffectLedger ledger) {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("[测试场景] " + title);
        System.out.println("[请求标识] requestId=" + requestId);
        System.out.println("[编排终态] " + instance.status());
        System.out.println("[参与方执行/补偿流水]（只记录真正落地的副作用）");
        for (LedgerEntry e : ledger.entries()) {
            if (e.requestId().equals(requestId)) {
                System.out.println("    - " + e.participant()
                        + " " + e.direction() + " " + e.operation()
                        + " @ " + e.timestampMs());
            }
        }
        System.out.println("[编排判定流水]");
        for (HistoryEvent e : instance.history()) {
            String step = e.stepName() == null ? "-" : e.stepName();
            System.out.println("    #" + e.seq() + " [" + e.eventType()
                    + "] step=" + step + (e.detail() == null ? "" : " | " + e.detail()));
        }
        System.out.println("[各步骤状态]");
        instance.steps().forEach(s -> System.out.println(
                "    - " + s.stepName() + " = " + s.status()
                        + (s.failureMessage() == null ? "" : " (" + s.failureMessage() + ")")));
        System.out.println("================================================================");
    }

    /** 断言某请求在某参与方的某方向副作用恰好 1 次（无重复、无丢失）。 */
    static void assertEffectOnce(SideEffectLedger ledger, String requestId,
                                 String participant, String direction) {
        long count = ledger.count(requestId, participant, direction);
        Assertions.assertEquals(1, count,
                "请求 " + requestId + " 在参与方 " + participant + " 的 " + direction
                        + " 副作用应恰好1次，实际" + count + "次");
    }

    /** 断言某请求在某参与方的某方向没有副作用。 */
    static void assertEffectAbsent(SideEffectLedger ledger, String requestId,
                                   String participant, String direction) {
        long count = ledger.count(requestId, participant, direction);
        Assertions.assertEquals(0, count,
                "请求 " + requestId + " 在参与方 " + participant + " 的 " + direction
                        + " 不应存在副作用，实际" + count + "次");
    }
}
