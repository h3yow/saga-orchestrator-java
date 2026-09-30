package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.core.*;
import com.github.h3yow.sagaorchestratorjava.participant.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;

import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.COMPENSATE;
import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.EXECUTE;

/**
 * 场景6：第二种业务流程（账户开立，四步）与下单流程（三步）并存。
 *
 * <ul>
 *   <li>按 businessType 精确选中对应流程：开户为 kyc/account/card/notification 四步；</li>
 *   <li>开户流程自身的正向、逆序补偿、幂等去重独立成立；</li>
 *   <li>两种流程可同时跑、互不影响（参与方组合不重叠）；</li>
 *   <li>未注册 / 空白业务类型明确拒绝（IllegalArgumentException），不静默套用默认流程。</li>
 * </ul>
 */
@SpringBootTest
class MultiFlowTest {

    @Autowired SagaService sagaService;
    @Autowired InMemorySagaRepository repository;
    @Autowired SideEffectLedger ledger;
    @Autowired ResilientCaller caller;
    @Autowired InventoryParticipant inventory;
    @Autowired PaymentParticipant payment;
    @Autowired ShippingParticipant shipping;
    @Autowired KycParticipant kyc;
    @Autowired AccountParticipant account;
    @Autowired CardParticipant card;
    @Autowired NotificationParticipant notification;

    private AbstractSimulatedParticipant[] allParticipants() {
        return new AbstractSimulatedParticipant[]{
                inventory, payment, shipping, kyc, account, card, notification};
    }

    /** 开户四步全部成功：步骤数=4，参与方组合与下单完全不同。 */
    @Test
    void accountOpeningFlow_fourStepsAllSucceed() {
        String requestId = TestSupport.newRequestId("acct-happy");
        TestSupport.resetAll(repository, ledger, caller, allParticipants());

        SagaInstance instance = sagaService.submit(requestId, AccountOpeningSagaFactory.BUSINESS_TYPE);

        TestSupport.printReport("开户流程四步全部成功", requestId, instance, ledger);

        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPLETED, instance.status());
        org.junit.jupiter.api.Assertions.assertEquals(4, instance.stepCount(), "开户流程必须是四步");
        TestSupport.assertEffectOnce(ledger, requestId, "kyc", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "account", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "card", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "notification", EXECUTE);
        // 下单流程的参与方不应被触碰
        for (String p : Arrays.asList("inventory", "payment", "shipping")) {
            TestSupport.assertEffectAbsent(ledger, requestId, p, EXECUTE);
        }
        TestSupport.assertEffectAbsent(ledger, requestId, "kyc", COMPENSATE);
    }

    /** 开户流程第 3 步（绑卡）业务失败：前两步逆序补偿，第 4 步从未执行。 */
    @Test
    void accountOpeningFlow_thirdStepFails_rollsBackFirstTwo() {
        String requestId = TestSupport.newRequestId("acct-rollback");
        TestSupport.resetAll(repository, ledger, caller, allParticipants());

        card.setFaultPlan(FaultPlan.none()
                .failFrom(1, EXECUTE, FailureType.BUSINESS, "银行卡校验不通过，业务拒绝绑卡"));

        SagaInstance instance = sagaService.submit(requestId, AccountOpeningSagaFactory.BUSINESS_TYPE);
        StepState cardState = instance.stepState("bind-card");

        TestSupport.printReport("开户绑卡业务失败，逆序补偿前两步", requestId, instance, ledger);

        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPENSATED, instance.status());
        org.junit.jupiter.api.Assertions.assertEquals(FailureType.BUSINESS, cardState.failureType());
        // kyc / account 已生效 -> 各补偿恰好1次（逆序：先 account 后 kyc）
        TestSupport.assertEffectOnce(ledger, requestId, "kyc", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "account", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "account", COMPENSATE);
        TestSupport.assertEffectOnce(ledger, requestId, "kyc", COMPENSATE);
        // 绑卡未生效，通知未执行
        TestSupport.assertEffectAbsent(ledger, requestId, "card", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "notification", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "notification", COMPENSATE);
    }

    /** 开户流程终态后重复提交：幂等成立，四步正向各1次，补偿0次。 */
    @Test
    void accountOpeningFlow_duplicateSubmit_isIdempotent() {
        String requestId = TestSupport.newRequestId("acct-dup");
        TestSupport.resetAll(repository, ledger, caller, allParticipants());

        SagaInstance first = sagaService.submit(requestId, AccountOpeningSagaFactory.BUSINESS_TYPE);
        SagaInstance second = sagaService.submit(requestId, AccountOpeningSagaFactory.BUSINESS_TYPE);

        TestSupport.printReport("开户流程重复提交幂等", requestId, second, ledger);

        org.junit.jupiter.api.Assertions.assertSame(first, second);
        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPLETED, second.status());
        for (String p : Arrays.asList("kyc", "account", "card", "notification")) {
            TestSupport.assertEffectOnce(ledger, requestId, p, EXECUTE);
            TestSupport.assertEffectAbsent(ledger, requestId, p, COMPENSATE);
        }
    }

    /** 未注册的业务类型必须明确拒绝，不能默默套用默认流程。 */
    @Test
    void unknownBusinessType_isRejected() {
        TestSupport.resetAll(repository, ledger, caller, allParticipants());

        String requestId = TestSupport.newRequestId("unknown-type");
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> sagaService.submit(requestId, "MONEY_LAUNDERING"),
                "未注册业务类型必须被拒绝");
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("未注册的业务类型"));
        org.junit.jupiter.api.Assertions.assertTrue(sagaService.query(requestId).isEmpty(),
                "被拒绝的请求不应创建任何编排实例");

        // 空白业务类型同样拒绝
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> sagaService.submit(requestId + "-blank", "  "));
        System.out.println("[业务类型判定] 未注册类型被拒绝: " + ex.getMessage());
    }

    /** 同一 requestId 先按下单受理、再以开户提交：必须拒绝，禁止换流程复用实例。 */
    @Test
    void sameRequestIdWithDifferentBusinessType_isRejected() {
        String requestId = TestSupport.newRequestId("type-mismatch");
        TestSupport.resetAll(repository, ledger, caller, allParticipants());

        SagaInstance first = sagaService.submit(requestId, OrderSagaFactory.BUSINESS_TYPE);
        org.junit.jupiter.api.Assertions.assertEquals(3, first.stepCount());

        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> sagaService.submit(requestId, AccountOpeningSagaFactory.BUSINESS_TYPE),
                "同一 requestId 不允许换业务类型提交");
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("不能改用业务类型"));

        // 原实例仍是下单流程、终态不变
        SagaInstance again = sagaService.query(requestId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertSame(first, again);
        org.junit.jupiter.api.Assertions.assertEquals(SagaStatus.COMPLETED, again.status());
        org.junit.jupiter.api.Assertions.assertEquals(3, again.stepCount());
        System.out.println("[业务类型判定] 同标识换类型被拒绝: " + ex.getMessage());
    }
}
