package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.core.*;
import com.github.h3yow.sagaorchestratorjava.participant.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.COMPENSATE;
import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.EXECUTE;

/**
 * 第二种业务流程（差旅预订 TRAVEL_BOOKING，四步、参与方组合与下单完全不同）：
 * <ol>
 *   <li>四步全部成功：终态 COMPLETED，flight/hotel/visa/loyalty 正向各 1 次、无补偿；</li>
 *   <li>第 3 步 visa 业务失败：前两步逆序补偿，第 4 步从未执行，无部分生效；</li>
 *   <li>未注册业务类型明确拒绝（IllegalArgumentException），不套用默认流程。</li>
 * </ol>
 */
@SpringBootTest
class TravelSagaTest {

    @Autowired SagaService sagaService;
    @Autowired InMemorySagaRepository repository;
    @Autowired SideEffectLedger ledger;
    @Autowired ResilientCaller caller;
    @Autowired InventoryParticipant inventory;
    @Autowired PaymentParticipant payment;
    @Autowired ShippingParticipant shipping;
    @Autowired FlightParticipant flight;
    @Autowired HotelParticipant hotel;
    @Autowired VisaParticipant visa;
    @Autowired LoyaltyParticipant loyalty;

    private void resetAll() {
        TestSupport.resetAll(repository, ledger, caller,
                inventory, payment, shipping, flight, hotel, visa, loyalty);
    }

    @Test
    void fourStepTravelFlow_allSucceed() {
        String requestId = TestSupport.newRequestId("travel-happy");
        resetAll();

        SagaInstance instance = sagaService.submit(requestId, TravelSagaFactory.BUSINESS_TYPE);

        TestSupport.printReport("差旅四步流程全部成功", requestId, instance, ledger);

        Assertions.assertEquals(SagaStatus.COMPLETED, instance.status());
        Assertions.assertEquals(4, instance.stepCount(), "差旅流程必须是四步");
        Assertions.assertEquals(TravelSagaFactory.BUSINESS_TYPE, instance.definition().type());
        TestSupport.assertEffectOnce(ledger, requestId, "flight", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "hotel", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "visa", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "loyalty", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "flight", COMPENSATE);
        TestSupport.assertEffectAbsent(ledger, requestId, "loyalty", COMPENSATE);

        // 与下单流程的参与方完全不交叉
        TestSupport.assertEffectAbsent(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "shipping", EXECUTE);
    }

    @Test
    void visaBusinessFails_firstTwoStepsCompensatedInReverse_fourthNotRun() {
        String requestId = TestSupport.newRequestId("travel-rollback");
        resetAll();

        visa.setFaultPlan(FaultPlan.none()
                .failFrom(1, EXECUTE, FailureType.BUSINESS, "签证材料不全，业务拒签"));

        SagaInstance instance = sagaService.submit(requestId, TravelSagaFactory.BUSINESS_TYPE);
        StepState visaState = instance.stepState("apply-visa");

        TestSupport.printReport("差旅流程签证业务失败，机票/酒店逆序补偿", requestId, instance, ledger);

        Assertions.assertEquals(SagaStatus.COMPENSATED, instance.status());
        Assertions.assertEquals(FailureType.BUSINESS, visaState.failureType());
        // flight、hotel 已生效 -> 各正向1次、补偿1次
        TestSupport.assertEffectOnce(ledger, requestId, "flight", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "hotel", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "hotel", COMPENSATE);
        TestSupport.assertEffectOnce(ledger, requestId, "flight", COMPENSATE);
        // visa 未生效，loyalty 从未执行
        TestSupport.assertEffectAbsent(ledger, requestId, "visa", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "loyalty", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "loyalty", COMPENSATE);

        // 流水可追溯：补偿顺序必须 hotel 在前、flight 在后（逆序）
        long hotelCompTs = ledger.entries().stream()
                .filter(e -> e.requestId().equals(requestId) && "hotel".equals(e.participant())
                        && COMPENSATE.equals(e.direction()))
                .findFirst().orElseThrow().timestampMs();
        long flightCompTs = ledger.entries().stream()
                .filter(e -> e.requestId().equals(requestId) && "flight".equals(e.participant())
                        && COMPENSATE.equals(e.direction()))
                .findFirst().orElseThrow().timestampMs();
        Assertions.assertTrue(hotelCompTs <= flightCompTs,
                "补偿必须逆序：hotel 先于 flight");
    }

    @Test
    void unknownBusinessType_isRejected_notSilentlyDefaulted() {
        resetAll();
        String requestId = TestSupport.newRequestId("travel-unknown");

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> sagaService.submit(requestId, "NOT_A_REAL_BUSINESS"));

        System.out.println();
        System.out.println("================================================================");
        System.out.println("[测试场景] 未注册业务类型必须明确拒绝");
        System.out.println("[请求标识] requestId=" + requestId);
        System.out.println("[判定依据] " + ex.getMessage());
        System.out.println("================================================================");

        Assertions.assertTrue(repository.find(requestId).isEmpty(),
                "被拒绝的请求不允许创建任何实例（不能默默套用默认流程）");
        Assertions.assertTrue(ex.getMessage().contains("NOT_A_REAL_BUSINESS"));
        // 没有任何副作用产生
        Assertions.assertTrue(ledger.entries().stream().noneMatch(e -> e.requestId().equals(requestId)));
    }
}
