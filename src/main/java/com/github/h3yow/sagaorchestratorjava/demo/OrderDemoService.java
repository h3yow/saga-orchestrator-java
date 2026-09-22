package com.github.h3yow.sagaorchestratorjava.demo;

import com.github.h3yow.sagaorchestratorjava.mock.MockParticipant;
import com.github.h3yow.sagaorchestratorjava.mock.MockScenario;
import com.github.h3yow.sagaorchestratorjava.participant.ParticipantLedger;
import com.github.h3yow.sagaorchestratorjava.resilience.ResilienceCaller;
import com.github.h3yow.sagaorchestratorjava.saga.SagaContext;
import com.github.h3yow.sagaorchestratorjava.saga.SagaDefinition;
import com.github.h3yow.sagaorchestratorjava.saga.SagaInstance;
import com.github.h3yow.sagaorchestratorjava.saga.SagaOrchestrator;
import com.github.h3yow.sagaorchestratorjava.saga.SagaRepository;
import com.github.h3yow.sagaorchestratorjava.saga.SagaStep;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 演示用下单编排入口：构造三步 SagaDefinition 并提交给编排器。
 * 参与方行为可通过 {@code configureParticipant} 在运行时调整（测试/演示故障注入）。
 */
@Service
public class OrderDemoService {

    public static final String SAGA_TYPE = "order-creation";

    private final SagaOrchestrator orchestrator;
    private final MockParticipant inventory;
    private final MockParticipant payment;
    private final MockParticipant shipping;

    public OrderDemoService(SagaOrchestrator orchestrator,
                            MockParticipant inventory,
                            MockParticipant payment,
                            MockParticipant shipping) {
        this.orchestrator = orchestrator;
        this.inventory = inventory;
        this.payment = payment;
        this.shipping = shipping;
    }

    public SagaDefinition definition() {
        return new SagaDefinition(SAGA_TYPE, List.of(
                new SagaStep("deduct-inventory", inventory),
                new SagaStep("charge-payment", payment),
                new SagaStep("arrange-shipping", shipping)));
    }

    public SagaInstance submitOrder(String requestId) {
        return orchestrator.submit(requestId, definition(), new SagaContext(requestId));
    }

    /** 全量重置：参与方剧本与计数、熔断状态、实例仓库、流水台账（测试隔离用）。 */
    public void resetAll(ParticipantLedger ledger,
                         ResilienceCaller caller,
                         SagaRepository repository) {
        inventory.reset();
        payment.reset();
        shipping.reset();
        caller.resetCircuitBreakers();
        repository.reset();
        ledger.clear();
    }

    public void configureParticipant(String participant, MockScenario scenario) {
        switch (participant) {
            case OrderSagaParticipants.INVENTORY -> inventory.useScenario(scenario);
            case OrderSagaParticipants.PAYMENT -> payment.useScenario(scenario);
            case OrderSagaParticipants.SHIPPING -> shipping.useScenario(scenario);
            default -> throw new IllegalArgumentException("unknown participant: " + participant);
        }
    }

    /** 配置某参与方补偿阶段的行为（null = 补偿始终可靠成功）。 */
    public void configureCompensation(String participant, MockScenario scenario) {
        switch (participant) {
            case OrderSagaParticipants.INVENTORY -> inventory.useCompensateScenario(scenario);
            case OrderSagaParticipants.PAYMENT -> payment.useCompensateScenario(scenario);
            case OrderSagaParticipants.SHIPPING -> shipping.useCompensateScenario(scenario);
            default -> throw new IllegalArgumentException("unknown participant: " + participant);
        }
    }

    /** 参与方物理调用次数（含超时/重试），用于断言有界重试次数。 */
    public int forwardCallCount(String participant) {
        return switch (participant) {
            case OrderSagaParticipants.INVENTORY -> inventory.forwardCallCount();
            case OrderSagaParticipants.PAYMENT -> payment.forwardCallCount();
            case OrderSagaParticipants.SHIPPING -> shipping.forwardCallCount();
            default -> throw new IllegalArgumentException("unknown participant: " + participant);
        };
    }

    /** 参与方物理补偿调用次数（含重试）。 */
    public int compensateCallCount(String participant) {
        return switch (participant) {
            case OrderSagaParticipants.INVENTORY -> inventory.compensateCallCount();
            case OrderSagaParticipants.PAYMENT -> payment.compensateCallCount();
            case OrderSagaParticipants.SHIPPING -> shipping.compensateCallCount();
            default -> throw new IllegalArgumentException("unknown participant: " + participant);
        };
    }
}
