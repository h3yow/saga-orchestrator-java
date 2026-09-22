package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.demo.OrderDemoService;
import com.github.h3yow.sagaorchestratorjava.demo.OrderSagaParticipants;
import com.github.h3yow.sagaorchestratorjava.mock.MockScenario;
import com.github.h3yow.sagaorchestratorjava.participant.ParticipantLedger;
import com.github.h3yow.sagaorchestratorjava.resilience.ResilienceCaller;
import com.github.h3yow.sagaorchestratorjava.saga.SagaInstance;
import com.github.h3yow.sagaorchestratorjava.saga.SagaRepository;
import com.github.h3yow.sagaorchestratorjava.saga.SagaStatus;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试公共基类：每个用例前重置参与方为“全部正常”并清空调用流水台账。
 */
@SpringBootTest
abstract class AbstractSagaTest {

    protected static final Logger log = LoggerFactory.getLogger(AbstractSagaTest.class);

    @Autowired
    protected OrderDemoService orderService;

    @Autowired
    protected ParticipantLedger ledger;

    @Autowired
    protected ResilienceCaller resilienceCaller;

    @Autowired
    protected SagaRepository repository;

    @BeforeEach
    void reset() {
        // 同一 Spring 上下文跨用例复用，必须全量重置：实例仓库、熔断、参与方计数/剧本、流水台账
        orderService.resetAll(ledger, resilienceCaller, repository);
    }

    /** 打印请求标识、执行/补偿流水与判定依据。 */
    protected void explain(String title, SagaInstance s) {
        log.info("==== {} | requestId={} finalStatus={} failedStep={} failureReason={}",
                title, s.requestId(), s.status(), s.failedStep(), s.failureReason());
        s.stepRecords().forEach(r -> log.info(
                "     step#{} '{}' status={} forwardAttempts={} compensateAttempts={} reason={}",
                r.index(), r.name(), r.status(), r.forwardAttempts(),
                r.compensateAttempts(), r.failureReason()));
        log.info("     LEDGER forward   (effective once each): {}", ledger.forwardEntries());
        log.info("     LEDGER compensate (effective once each): {}", ledger.compensateEntries());
    }

    protected void assertExactly(List<String> expected, List<String> actual) {
        assertEquals(expected.size(), actual.size(),
                "无重复且无丢失：期望 " + expected + " 实际 " + actual);
        assertTrue(actual.containsAll(expected) && expected.containsAll(actual),
                "流水集合不一致：期望 " + expected + " 实际 " + actual);
    }

    protected void assertTerminalConsistent(SagaInstance s, SagaStatus expected) {
        assertEquals(expected, s.status());
        assertTrue(s.isTerminal());
    }
}
