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
 * 业务类型（businessType）受理规则的边界测试：
 * <ol>
 *   <li>空串 ""、纯空白 "   "：视为“带了字段但内容为空”，明确拒绝，不建实例、不留任何副作用与流水；</li>
 *   <li>未注册类型：明确拒绝，不静默套用默认流程；</li>
 *   <li>完全不带 businessType（null）：按历史契约默认走下单流程 ORDER_PLACEMENT；</li>
 *   <li>合法已注册类型（大小写不同、首尾带空白）：正常命中对应流程，不被收紧误伤；</li>
 *   <li>同一 requestId 换业务类型重复提交：明确拒绝，原实例终态与流水不受影响。</li>
 * </ol>
 * 每个用例的日志都会打印提交的业务类型、命中/拒绝的判定依据。
 */
@SpringBootTest
class BusinessTypeValidationTest {

    @Autowired SagaService sagaService;
    @Autowired InMemorySagaRepository repository;
    @Autowired SideEffectLedger ledger;
    @Autowired ResilientCaller caller;
    @Autowired SagaDefinitionRegistry registry;
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

    private static void printRejection(String scenario, String requestId,
                                       String businessType, Exception ex) {
        System.out.println();
        System.out.println("================================================================");
        System.out.println("[测试场景] " + scenario);
        System.out.println("[请求标识] requestId=" + requestId);
        System.out.println("[提交的业务类型] '" + businessType + "'");
        System.out.println("[判定结果] 拒绝 (" + ex.getClass().getSimpleName() + ")");
        System.out.println("[判定依据] " + ex.getMessage());
        System.out.println("================================================================");
    }

    private void assertRejectedCleanly(String requestId) {
        Assertions.assertTrue(repository.find(requestId).isEmpty(),
                "被拒绝的请求不允许创建任何实例");
        Assertions.assertTrue(ledger.entries().stream().noneMatch(e -> e.requestId().equals(requestId)),
                "被拒绝的请求不允许留下任何正向或补偿副作用流水");
    }

    @Test
    void emptyStringBusinessType_isRejected_noInstanceNoSideEffects() {
        resetAll();
        String requestId = TestSupport.newRequestId("bt-empty");

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> sagaService.submit(requestId, ""));

        printRejection("空串业务类型必须拒绝（不能回退到下单流程）", requestId, "", ex);
        assertRejectedCleanly(requestId);
    }

    @Test
    void blankWhitespaceBusinessType_isRejected_noInstanceNoSideEffects() {
        resetAll();
        String requestId = TestSupport.newRequestId("bt-blank");

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> sagaService.submit(requestId, "   "));

        printRejection("纯空白业务类型必须拒绝（不能回退到下单流程）", requestId, "   ", ex);
        assertRejectedCleanly(requestId);
    }

    @Test
    void unregisteredBusinessType_isRejected_noInstanceNoSideEffects() {
        resetAll();
        String requestId = TestSupport.newRequestId("bt-unknown");

        IllegalArgumentException ex = Assertions.assertThrows(IllegalArgumentException.class,
                () -> sagaService.submit(requestId, "REFUND_ONLY"));

        printRejection("未注册业务类型必须拒绝", requestId, "REFUND_ONLY", ex);
        assertRejectedCleanly(requestId);
        Assertions.assertTrue(ex.getMessage().contains("REFUND_ONLY"),
                "报错信息必须带上被拒绝的类型，便于排查");
    }

    @Test
    void absentBusinessType_defaultsToOrderPlacement() {
        resetAll();
        String requestId = TestSupport.newRequestId("bt-default");

        SagaInstance instance = sagaService.submit(requestId, null);

        TestSupport.printReport("未携带 businessType（null）按历史契约默认走下单流程",
                requestId, instance, ledger);
        System.out.println("[判定依据] businessType=null（字段完全未携带）-> 默认 "
                + OrderSagaFactory.BUSINESS_TYPE + "；已注册类型=" + registry.registeredTypes());

        Assertions.assertEquals(SagaStatus.COMPLETED, instance.status());
        Assertions.assertEquals(OrderSagaFactory.BUSINESS_TYPE, instance.definition().type());
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "shipping", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "inventory", COMPENSATE);
    }

    @Test
    void registeredType_caseAndWhitespaceVariants_hitCorrectFlow() {
        resetAll();

        // 下单流程：小写 + 首尾空白
        String orderId = TestSupport.newRequestId("bt-order-variant");
        SagaInstance order = sagaService.submit(orderId, "  order_placement  ");
        TestSupport.printReport("合法类型变体 '  order_placement  ' 命中下单流程",
                orderId, order, ledger);
        System.out.println("[判定依据] 大小写不敏感 + 忽略首尾空白 -> "
                + OrderSagaFactory.BUSINESS_TYPE);
        Assertions.assertEquals(SagaStatus.COMPLETED, order.status());
        Assertions.assertEquals(OrderSagaFactory.BUSINESS_TYPE, order.definition().type());
        TestSupport.assertEffectOnce(ledger, orderId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, orderId, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, orderId, "shipping", EXECUTE);

        // 差旅流程：混合大小写 + 首尾空白
        String travelId = TestSupport.newRequestId("bt-travel-variant");
        SagaInstance travel = sagaService.submit(travelId, " Travel_Booking ");
        TestSupport.printReport("合法类型变体 ' Travel_Booking ' 命中差旅流程",
                travelId, travel, ledger);
        System.out.println("[判定依据] 大小写不敏感 + 忽略首尾空白 -> "
                + TravelSagaFactory.BUSINESS_TYPE);
        Assertions.assertEquals(SagaStatus.COMPLETED, travel.status());
        Assertions.assertEquals(TravelSagaFactory.BUSINESS_TYPE, travel.definition().type());
        Assertions.assertEquals(4, travel.stepCount(), "差旅流程必须是四步");
        TestSupport.assertEffectOnce(ledger, travelId, "flight", EXECUTE);
        TestSupport.assertEffectOnce(ledger, travelId, "hotel", EXECUTE);
        TestSupport.assertEffectOnce(ledger, travelId, "visa", EXECUTE);
        TestSupport.assertEffectOnce(ledger, travelId, "loyalty", EXECUTE);
        // 差旅请求绝不能触发下单流程的参与方
        TestSupport.assertEffectAbsent(ledger, travelId, "inventory", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, travelId, "payment", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, travelId, "shipping", EXECUTE);
    }

    @Test
    void sameRequestIdWithDifferentBusinessType_isRejected_originalUntouched() {
        resetAll();
        String requestId = TestSupport.newRequestId("bt-conflict");

        // 第一次：下单流程，跑到终态
        SagaInstance first = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);
        Assertions.assertEquals(SagaStatus.COMPLETED, first.status());
        int historySizeBefore = first.history().size();

        // 第二次：同一 requestId 换差旅流程，必须明确拒绝
        IllegalStateException ex = Assertions.assertThrows(IllegalStateException.class,
                () -> sagaService.submit(requestId, TravelSagaFactory.BUSINESS_TYPE));

        System.out.println();
        System.out.println("================================================================");
        System.out.println("[测试场景] 同一 requestId 换业务类型重复提交必须拒绝");
        System.out.println("[请求标识] requestId=" + requestId);
        System.out.println("[首次业务类型] " + OrderSagaFactory.BUSINESS_TYPE
                + " -> 终态 " + first.status());
        System.out.println("[再次提交业务类型] " + TravelSagaFactory.BUSINESS_TYPE);
        System.out.println("[判定结果] 拒绝 (" + ex.getClass().getSimpleName() + ")");
        System.out.println("[判定依据] " + ex.getMessage());
        System.out.println("================================================================");

        // 原实例仍是下单流程的终态，且没有因被拒绝的请求新增任何流水
        SagaInstance after = repository.find(requestId).orElseThrow();
        Assertions.assertEquals(OrderSagaFactory.BUSINESS_TYPE, after.definition().type());
        Assertions.assertEquals(SagaStatus.COMPLETED, after.status());
        Assertions.assertEquals(historySizeBefore, after.history().size(),
                "被拒绝的请求不允许在已有实例上追加任何流水（含 SUBMIT_DEDUPED）");
        // 差旅参与方零副作用
        TestSupport.assertEffectAbsent(ledger, requestId, "flight", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "hotel", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "visa", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "loyalty", EXECUTE);
        // 下单参与方仍各恰好 1 次正向、无补偿
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "shipping", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "payment", COMPENSATE);
    }

    @Test
    void sameRequestIdWithSameBusinessTypeVariant_stillDedupes() {
        resetAll();
        String requestId = TestSupport.newRequestId("bt-dedup-variant");

        SagaInstance first = sagaService.submit(requestId, TravelSagaFactory.BUSINESS_TYPE);
        SagaInstance again = sagaService.submit(requestId, " travel_booking ");

        TestSupport.printReport("同一 requestId 以类型变体重复提交仍按幂等复用",
                requestId, again, ledger);
        System.out.println("[判定依据] '" + TravelSagaFactory.BUSINESS_TYPE
                + "' 与 ' travel_booking ' 归一化后同型 -> 幂等复用，不重复执行");

        Assertions.assertSame(first, again, "同型重复提交必须复用同一实例");
        Assertions.assertEquals(SagaStatus.COMPLETED, again.status());
        TestSupport.assertEffectOnce(ledger, requestId, "flight", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "hotel", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "visa", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "loyalty", EXECUTE);
    }
}
