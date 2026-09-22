package com.github.h3yow.sagaorchestratorjava.demo;

import com.github.h3yow.sagaorchestratorjava.mock.MockParticipant;
import com.github.h3yow.sagaorchestratorjava.participant.ParticipantLedger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 下单编排的三个本地模拟参与方：
 * 扣库存(inventory) -> 扣支付(payment) -> 安排发货(shipping)。
 * 每步失败后按逆序补偿（取消发货 -> 退款 -> 回补库存）。
 */
@Configuration
public class OrderSagaParticipants {

    public static final String INVENTORY = "inventory";
    public static final String PAYMENT = "payment";
    public static final String SHIPPING = "shipping";

    @Bean
    public MockParticipant inventory(ParticipantLedger ledger) {
        return new MockParticipant(INVENTORY, ledger);
    }

    @Bean
    public MockParticipant payment(ParticipantLedger ledger) {
        return new MockParticipant(PAYMENT, ledger);
    }

    @Bean
    public MockParticipant shipping(ParticipantLedger ledger) {
        return new MockParticipant(SHIPPING, ledger);
    }
}
