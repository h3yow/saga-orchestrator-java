package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.core.*;
import com.github.h3yow.sagaorchestratorjava.participant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.COMPENSATE;
import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.EXECUTE;

/**
 * 场景0：无故障时三步全部成功。
 * 判定依据：终态 COMPLETED；库存/支付/发货三个参与方 EXECUTE 各恰好1次，无任何 COMPENSATE。
 */
@SpringBootTest
class HappyPathTest {

    @Autowired SagaService sagaService;
    @Autowired InMemorySagaRepository repository;
    @Autowired SideEffectLedger ledger;
    @Autowired ResilientCaller caller;
    @Autowired InventoryParticipant inventory;
    @Autowired PaymentParticipant payment;
    @Autowired ShippingParticipant shipping;

    @Test
    void allStepsSucceed() {
        String requestId = TestSupport.newRequestId("happy");
        TestSupport.resetAll(repository, ledger, caller, inventory, payment, shipping);

        SagaInstance instance = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);

        TestSupport.printReport("全正向成功", requestId, instance, ledger);

        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPLETED, instance.status());
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "shipping", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "inventory", COMPENSATE);
        TestSupport.assertEffectAbsent(ledger, requestId, "payment", COMPENSATE);
        TestSupport.assertEffectAbsent(ledger, requestId, "shipping", COMPENSATE);
    }
}
