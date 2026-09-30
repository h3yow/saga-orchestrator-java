package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.core.*;
import com.github.h3yow.sagaorchestratorjava.participant.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.COMPENSATE;
import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.EXECUTE;

/**
 * 高并发下混合“成功 / 失败补偿”：
 * <ul>
 *   <li>payment 对所有请求业务失败：16 个请求各由 2 个线程并发重复提交（共 32 提交），
 *       全部应在第 2 步失败、回滚第 1 步；并发补偿互不串台，每个已生效步骤补偿恰好一次；</li>
 *   <li>熔断阈值特意调到高于失败总数（100），保证每次都是真实业务失败 BUSINESS，
 *       分类确定；熔断快速失败路径由 {@code CircuitBreakerTest} 单独覆盖。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "saga.resilience.circuit-failure-threshold=100"
})
class ConcurrentCompensationTest {

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

    @Test
    void concurrentFailures_eachCompensatedIndependently_exactlyOnce() throws Exception {
        TestSupport.resetAll(repository, ledger, caller,
                inventory, payment, shipping, flight, hotel, visa, loyalty);
        inventory.setCallDelayMillis(10);
        payment.setCallDelayMillis(10);

        int count = 16;
        // payment 对所有请求业务失败：16 个并发下单都应在第 2 步失败、回滚第 1 步
        payment.setFaultPlan(FaultPlan.none()
                .failFrom(1, EXECUTE, FailureType.BUSINESS, "支付系统批量演练：业务拒绝扣款"));

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(TestSupport.newRequestId("cc-comp-" + i));
        }

        int threads = 32; // 每个请求再由 2 个线程重复提交，验证补偿幂等
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SagaInstance>> futures = new ArrayList<>();
        for (String id : ids) {
            for (int dup = 0; dup < 2; dup++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return sagaService.submit(id, OrderSagaFactory.BUSINESS_TYPE);
                }));
            }
        }
        start.countDown();

        for (Future<SagaInstance> f : futures) {
            SagaInstance instance = f.get(30, TimeUnit.SECONDS);
            Assertions.assertEquals(SagaStatus.COMPENSATED, instance.status());
        }
        pool.shutdown();

        System.out.println();
        System.out.println("================================================================");
        System.out.println("[测试场景] 高并发失败补偿（" + count + "个请求 * 2次提交，payment 全部业务失败）");

        for (String id : ids) {
            SagaInstance instance = repository.find(id).orElseThrow();
            // 每个请求：inventory 正向1次、补偿1次；payment/shipping 无正向
            TestSupport.assertEffectOnce(ledger, id, "inventory", EXECUTE);
            TestSupport.assertEffectOnce(ledger, id, "inventory", COMPENSATE);
            TestSupport.assertEffectAbsent(ledger, id, "payment", EXECUTE);
            TestSupport.assertEffectAbsent(ledger, id, "shipping", EXECUTE);
            Assertions.assertEquals(FailureType.BUSINESS,
                    instance.stepState("charge-payment").failureType());
            Assertions.assertEquals(StepStatus.COMPENSATED,
                    instance.stepState("reserve-stock").status());
        }

        // 汇总：16 次库存正向、16 次补偿，没有多也没有少
        long execCount = ledger.entries().stream()
                .filter(e -> "inventory".equals(e.participant()) && EXECUTE.equals(e.direction())).count();
        long compCount = ledger.entries().stream()
                .filter(e -> "inventory".equals(e.participant()) && COMPENSATE.equals(e.direction())).count();
        System.out.println("[账本核对] inventory EXECUTE=" + execCount
                + " COMPENSATE=" + compCount + "（期望各 " + count + "）");
        Assertions.assertEquals(count, execCount);
        Assertions.assertEquals(count, compCount);
        Assertions.assertTrue(ledger.entries().stream()
                .noneMatch(e -> "payment".equals(e.participant()) || "shipping".equals(e.participant())));
        System.out.println("================================================================");
    }
}
