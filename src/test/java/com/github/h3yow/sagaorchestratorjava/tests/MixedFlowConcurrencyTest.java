package com.github.h3yow.sagaorchestratorjava.tests;

import com.github.h3yow.sagaorchestratorjava.core.*;
import com.github.h3yow.sagaorchestratorjava.participant.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.COMPENSATE;
import static com.github.h3yow.sagaorchestratorjava.participant.AbstractSimulatedParticipant.EXECUTE;

/**
 * 两种业务流程混合并发提交：
 * <ul>
 *   <li>32 个线程同时起跑：其中既有同一 requestId 的重复提交（下单、差旅各一个热点标识，
 *       多线程撞同一标识），也有大量不同标识；下单(3步)与差旅(4步)混在一起；</li>
 *   <li>同一标识只真正执行一次（所有线程拿到同一实例、同一终态，副作用各方向恰好 0/1 次）；</li>
 *   <li>不同标识之间不得互相阻塞到接近串行：给每个参与方加固定处理耗时，
 *       若串行执行总耗时会线性累加，实测总耗时应明显小于串行下界；</li>
 *   <li>两种流程的正向执行、逆序补偿、幂等去重各自独立成立。</li>
 * </ul>
 */
@SpringBootTest
class MixedFlowConcurrencyTest {

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

    private static final int THREADS = 32;

    /** 每个线程提交的 (requestId, businessType)。 */
    private record Job(String requestId, String businessType) {
    }

    @Test
    void thirtyTwoThreads_mixedFlows_duplicatesAndDistinct_runIndependently() throws Exception {
        TestSupport.resetAll(repository, ledger, caller,
                inventory, payment, shipping, flight, hotel, visa, loyalty);

        // 每个参与方每次正常调用耗时 40ms：
        // 串行下界 = 所有实例步骤数之和 * 40ms，远大于并行耗时。
        long delay = 40L;
        inventory.setCallDelayMillis(delay);
        payment.setCallDelayMillis(delay);
        shipping.setCallDelayMillis(delay);
        flight.setCallDelayMillis(delay);
        hotel.setCallDelayMillis(delay);
        visa.setCallDelayMillis(delay);
        loyalty.setCallDelayMillis(delay);

        String hotOrder = TestSupport.newRequestId("hot-order");
        String hotTravel = TestSupport.newRequestId("hot-travel");

        // 构造 32 个任务：热点标识各占 5 个线程，其余 22 个为独立标识，两种流程交替
        List<Job> jobs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            jobs.add(new Job(hotOrder, OrderSagaFactory.BUSINESS_TYPE));
            jobs.add(new Job(hotTravel, TravelSagaFactory.BUSINESS_TYPE));
        }
        int distinct = THREADS - jobs.size(); // 22
        for (int i = 0; i < distinct; i++) {
            String type = (i % 2 == 0)
                    ? OrderSagaFactory.BUSINESS_TYPE
                    : TravelSagaFactory.BUSINESS_TYPE;
            jobs.add(new Job(TestSupport.newRequestId("mix-" + i), type));
        }
        Assertions.assertEquals(THREADS, jobs.size());

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SagaInstance>> futures = new ArrayList<>();
        long begin = System.currentTimeMillis();
        for (Job job : jobs) {
            futures.add(pool.submit(() -> {
                start.await();
                return sagaService.submit(job.requestId(), job.businessType());
            }));
        }
        start.countDown();

        Map<String, SagaInstance> byId = new HashMap<>();
        for (Future<SagaInstance> f : futures) {
            SagaInstance instance = f.get(30, TimeUnit.SECONDS);
            // 同一 requestId 的所有线程必须拿到同一实例
            SagaInstance existing = byId.putIfAbsent(instance.requestId(), instance);
            if (existing != null) {
                Assertions.assertSame(existing, instance,
                        "同一 requestId 并发提交必须复用同一实例: " + instance.requestId());
            }
        }
        long elapsed = System.currentTimeMillis() - begin;
        pool.shutdown();

        // 唯一实例数 = 2 个热点 + 22 个独立标识
        long distinctIds = jobs.stream().map(Job::requestId).distinct().count();
        Assertions.assertEquals(distinctIds, byId.size());
        Assertions.assertEquals(24, distinctIds);

        System.out.println();
        System.out.println("================================================================");
        System.out.println("[测试场景] 32线程混合提交（两种流程 + 同标识重复 + 不同标识）");
        System.out.println("[并发结果] 唯一实例数=" + byId.size() + " 总耗时=" + elapsed + "ms");

        // 全部终态且自洽
        long orderCount = 0, travelCount = 0;
        for (SagaInstance instance : byId.values()) {
            Assertions.assertTrue(instance.isTerminal(),
                    "所有实例必须到达终态: " + instance.requestId() + " -> " + instance.status());
            Assertions.assertEquals(SagaStatus.COMPLETED, instance.status());
            if (OrderSagaFactory.BUSINESS_TYPE.equals(instance.definition().type())) {
                orderCount++;
                assertOrderEffects(instance.requestId());
            } else {
                travelCount++;
                assertTravelEffects(instance.requestId());
            }
            assertSequenceContiguous(instance);
        }
        System.out.println("[流程分布] 下单=" + orderCount + " 差旅=" + travelCount);
        TestSupport.assertEffectOnce(ledger, hotOrder, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, hotOrder, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, hotOrder, "shipping", EXECUTE);
        TestSupport.assertEffectOnce(ledger, hotTravel, "flight", EXECUTE);
        TestSupport.assertEffectOnce(ledger, hotTravel, "loyalty", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, hotTravel, "flight", COMPENSATE);

        // 并行性：总步骤数 = 12*3 + 12*4 = 84（12 个下单实例、12 个差旅实例）
        // 串行下界 84*40=3360ms；32 线程并行（每实例内部仍顺序），
        // 下单链 3*40=120ms、差旅链 4*40=160ms，理论并行墙钟约 ~160ms。
        long serialLowerBoundMs = 84L * delay;
        System.out.println("[并行判定] 串行下界=" + serialLowerBoundMs + "ms，实际=" + elapsed
                + "ms（不同标识必须并行，不能接近串行）");
        Assertions.assertTrue(elapsed < serialLowerBoundMs / 2,
                "不同标识之间几乎完全并行，实际耗时 " + elapsed
                        + "ms 不应接近串行下界 " + serialLowerBoundMs + "ms");

        // 账本总条数：每个实例每步恰好 1 条正向、0 条补偿
        long expectedLedger = 84L;
        Assertions.assertEquals(expectedLedger, ledger.entries().size(),
                "全部副作用总数必须恰好=" + expectedLedger + "（无重复、无丢失）");
        Assertions.assertTrue(ledger.entries().stream()
                .noneMatch(e -> COMPENSATE.equals(e.direction())), "成功场景不应有补偿");
        System.out.println("[参与方流水] 账本总条数=" + ledger.entries().size()
                + "（期望 " + expectedLedger + "，0 补偿）");
        System.out.println("================================================================");
    }

    private void assertOrderEffects(String requestId) {
        TestSupport.assertEffectOnce(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "shipping", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "inventory", COMPENSATE);
    }

    private void assertTravelEffects(String requestId) {
        TestSupport.assertEffectOnce(ledger, requestId, "flight", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "hotel", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "visa", EXECUTE);
        TestSupport.assertEffectOnce(ledger, requestId, "loyalty", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "flight", COMPENSATE);
        // 两种流程参与方不交叉
        TestSupport.assertEffectAbsent(ledger, requestId, "inventory", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "payment", EXECUTE);
        TestSupport.assertEffectAbsent(ledger, requestId, "shipping", EXECUTE);
    }

    /** 流水序号必须唯一、连续（1..N 无缺号无重复）。 */
    private void assertSequenceContiguous(SagaInstance instance) {
        List<HistoryEvent> events = instance.history();
        for (int i = 0; i < events.size(); i++) {
            Assertions.assertEquals(i + 1, events.get(i).seq(),
                    "流水序号必须连续: requestId=" + instance.requestId());
            Assertions.assertEquals(instance.requestId(), events.get(i).requestId());
        }
    }
}
