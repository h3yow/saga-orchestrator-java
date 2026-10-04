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
 * 业务类型解析与默认规则边界：
 * <ul>
 *   <li>空串、纯空白、未注册类型：明确拒绝，不建实例、无任何正向/补偿副作用与流水；</li>
 *   <li>完全不携带 businessType（null）：按历史契约默认走下单流程 ORDER_PLACEMENT；</li>
 *   <li>合法已注册类型（大小写不同、首尾带空白）：正确命中对应流程；</li>
 *   <li>同一 requestId 换业务类型再次提交：明确拒绝，首次流程终态不受影响。</li>
 * </ul>
 * 每个用例日志均打印：入参 businessType、判定结果、判定依据（仓储/账本/流水状态）。
 */
@SpringBootTest
class BusinessTypeResolutionTest {

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

    /** 打印拒绝类用例的判定依据：入参、异常、仓储与账本是否保持干净。 */
    private void printRejectionReport(String title, String requestId, String businessType, Exception e) {
        long ledgerCount = ledger.entries().stream().filter(en -> en.requestId().equals(requestId)).count();
        boolean instanceExists = repository.find(requestId).isPresent();
        System.out.println();
        System.out.println("================================================================");
        System.out.println("[测试场景] " + title);
        System.out.println("[请求标识] requestId=" + requestId);
        System.out.println("[入参业务类型] businessType=" + (businessType == null ? "<未携带>"
                : "\"" + businessType + "\""));
        System.out.println("[判定结果] 已拒绝: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        System.out.println("[判定依据] 仓储中实例存在=" + instanceExists + "（应为false），"
                + "该请求副作用流水条数=" + ledgerCount + "（应为0，含正向与补偿）");
        System.out.println("================================================================");
    }

    private void assertRejectedCleanly(String title, String requestId, String businessType,
                                       Class<? extends Exception> expected) {
        Exception e = Assertions.assertThrows(expected,
                () -> sagaService.submit(requestId, businessType),
                title + "：必须抛出 " + expected.getSimpleName());
        printRejectionReport(title, requestId, businessType, e);
        Assertions.assertTrue(repository.find(requestId).isEmpty(),
                title + "：被拒绝的请求不得创建实例");
        long ledgerCount = ledger.entries().stream()
                .filter(en -> en.requestId().equals(requestId)).count();
        Assertions.assertEquals(0, ledgerCount,
                title + "：被拒绝的请求不得留下任何正向或补偿副作用流水");
    }

    @Test
    void emptyStringBusinessType_rejected_noInstanceNoSideEffects() {
        String requestId = TestSupport.newRequestId("btype-empty");
        resetAll();
        assertRejectedCleanly("空串业务类型被拒绝", requestId, "", IllegalArgumentException.class);
    }

    @Test
    void blankWhitespaceBusinessType_rejected_noInstanceNoSideEffects() {
        String requestId = TestSupport.newRequestId("btype-blank");
        resetAll();
        assertRejectedCleanly("纯空白业务类型被拒绝", requestId, "   ", IllegalArgumentException.class);
    }

    @Test
    void unregisteredBusinessType_rejected_noInstanceNoSideEffects() {
        String requestId = TestSupport.newRequestId("btype-unknown");
        resetAll();
        assertRejectedCleanly("未注册业务类型被拒绝", requestId, "ORDER_REFUND", IllegalArgumentException.class);
    }

    @Test
    void missingBusinessType_defaultsToOrderPlacement() {
        String requestId = TestSupport.newRequestId("btype-default");
        resetAll();

        SagaInstance instance = sagaService.submit(requestId, null);

        TestSupport.printReport("完全未携带业务类型，默认走下单流程", requestId, instance, ledger);
        System.out.println("[判定依据] 入参 businessType=<未携带>，命中默认规则 -> "
                + instance.definition().type() + "，仅完全未携带时才允许默认");

        Assertions.assertEquals(SagaStatus.COMPLETED, instance.status());
        Assertions.assertEquals(OrderSagaFactory.BUSINESS_TYPE, instance.definition().type());
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "shipping", EXECUTE);
        // 不得误入差旅流程
        TestSupport.assertEffectAbsent(ledger, requestId, "flight", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "hotel", EXECUTE);
    }

    @Test
    void registeredTypeDifferentCase_resolvesToOrderFlow() {
        String requestId = TestSupport.newRequestId("btype-case");
        resetAll();

        SagaInstance instance = sagaService.submit(requestId, "order_placement");

        TestSupport.printReport("业务类型大小写不同仍命中下单流程", requestId, instance, ledger);
        System.out.println("[判定依据] 入参 businessType=\"order_placement\"，大小写不敏感匹配 -> "
                + instance.definition().type());

        Assertions.assertEquals(SagaStatus.COMPLETED, instance.status());
        Assertions.assertEquals(OrderSagaFactory.BUSINESS_TYPE, instance.definition().type());
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "shipping", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "flight", EXECUTE);
    }

    @Test
    void registeredTypeWithSurroundingWhitespace_resolvesToTravelFlow() {
        String requestId = TestSupport.newRequestId("btype-trim");
        resetAll();

        SagaInstance instance = sagaService.submit(requestId, "  TRAVEL_BOOKING  ");

        TestSupport.printReport("业务类型首尾带空白仍命中差旅流程", requestId, instance, ledger);
        System.out.println("[判定依据] 入参 businessType=\"  TRAVEL_BOOKING  \"，忽略首尾空白匹配 -> "
                + instance.definition().type());

        Assertions.assertEquals(SagaStatus.COMPLETED, instance.status());
        Assertions.assertEquals(TravelSagaFactory.BUSINESS_TYPE, instance.definition().type());
        TestSupport.assertEffectOnce(ledger, requestId, "flight", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "hotel", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "visa", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "loyalty", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "inventory", EXECUTE);
    }

    @Test
    void sameRequestIdDifferentBusinessType_rejected_firstFlowUntouched() {
        String requestId = TestSupport.newRequestId("btype-conflict");
        resetAll();

        SagaInstance first = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);
        Assertions.assertEquals(SagaStatus.COMPLETED, first.status());
        int historySize = first.history().size();

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> sagaService.submit(requestId, TravelSagaFactory.BUSINESS_TYPE),
                "同一 requestId 换业务类型必须被拒绝");

        System.out.println();
        System.out.println("================================================================");
        System.out.println("[测试场景] 同一requestId换业务类型再次提交被拒绝");
        System.out.println("[请求标识] requestId=" + requestId);
        System.out.println("[首次业务类型] " + OrderSagaFactory.BUSINESS_TYPE
                + " -> 终态 " + first.status());
        System.out.println("[再次提交业务类型] " + TravelSagaFactory.BUSINESS_TYPE
                + " -> 判定结果: 拒绝(" + e.getMessage() + ")");
        System.out.println("[判定依据] 首次实例终态=" + first.status() + "（应仍为COMPLETED），"
                + "流水条数=" + first.history().size() + "（应与拒绝前" + historySize + "一致），"
                + "差旅参与方副作用应为0");
        System.out.println("================================================================");

        Assertions.assertEquals(SagaStatus.COMPLETED, first.status(),
                "首次流程终态不得被第二次提交改变");
        Assertions.assertEquals(historySize, first.history().size(),
                "被拒绝的提交不得向已有实例追加任何流水");
        TestSupport.assertEffectAbsent(ledger, requestId, "flight", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "hotel", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "visa", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "loyalty", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "inventory", COMPENSATE);
        TestSupport.assertEffectAbsent(ledger, requestId, "payment", COMPENSATE);
    }

    @Test
    void sameRequestIdTravelThenOrder_rejected() {
        String requestId = TestSupport.newRequestId("btype-conflict-rev");
        resetAll();

        SagaInstance first = sagaService.submit(requestId, TravelSagaFactory.BUSINESS_TYPE);
        Assertions.assertEquals(SagaStatus.COMPLETED, first.status());

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE),
                "先差旅后下单同样必须被拒绝");

        System.out.println();
        System.out.println("================================================================");
        System.out.println("[测试场景] 同一requestId先差旅后下单被拒绝");
        System.out.println("[请求标识] requestId=" + requestId);
        System.out.println("[判定结果] 拒绝: " + e.getMessage());
        System.out.println("[判定依据] 首次实例终态=" + first.status()
                + "，下单参与方副作用应为0（不得套用首次流程之外的动作）");
        System.out.println("================================================================");

        Assertions.assertEquals(SagaStatus.COMPLETED, first.status());
        TestSupport.assertEffectAbsent(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "shipping", EXECUTE);
    }

    @Test
    void missingThenExplicitSameType_idempotentCompatible() {
        String requestId = TestSupport.newRequestId("btype-compat");
        resetAll();

        SagaInstance first = sagaService.submit(requestId, null);
        SagaInstance second = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);

        TestSupport.printReport("先缺省提交后显式同名类型，幂等兼容", requestId, second, ledger);
        System.out.println("[判定依据] 首次 businessType=<未携带> 默认 "
                + OrderSagaFactory.BUSINESS_TYPE + "，再次显式提交同名类型解析结果一致，"
                + "属历史契约兼容范围，应幂等命中而非拒绝");

        Assertions.assertSame(first, second, "解析结果一致时必须复用同一实例");
        Assertions.assertEquals(SagaStatus.COMPLETED, second.status());
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "shipping", EXECUTE);
    }
}
