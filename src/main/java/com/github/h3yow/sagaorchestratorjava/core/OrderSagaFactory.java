package com.github.h3yow.sagaorchestratorjava.core;

import com.github.h3yow.sagaorchestratorjava.participant.InventoryParticipant;
import com.github.h3yow.sagaorchestratorjava.participant.PaymentParticipant;
import com.github.h3yow.sagaorchestratorjava.participant.ShippingParticipant;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 构造“下单”业务的 Saga 定义：
 * <pre>
 *   1. inventory.reserveStock （预留库存）  补偿 releaseStock（释放库存）
 *   2. payment.charge         （扣款）      补偿 refund（退款）
 *   3. shipping.arrangeShipment（安排发货） 补偿 cancelShipment（取消发货）
 * </pre>
 * 任一步失败即按逆序补偿已生效步骤。
 */
@Component
public class OrderSagaFactory implements SagaFactory {

    public static final String BUSINESS_TYPE = "ORDER_PLACEMENT";

    private final InventoryParticipant inventory;
    private final PaymentParticipant payment;
    private final ShippingParticipant shipping;

    public OrderSagaFactory(InventoryParticipant inventory,
                            PaymentParticipant payment,
                            ShippingParticipant shipping) {
        this.inventory = inventory;
        this.payment = payment;
        this.shipping = shipping;
    }

    @Override
    public String businessType() {
        return BUSINESS_TYPE;
    }

    @Override
    public SagaDefinition create() {
        return new SagaDefinition(BUSINESS_TYPE, List.of(
                new SagaStep("reserve-stock", inventory, "reserveStock", "releaseStock"),
                new SagaStep("charge-payment", payment, "charge", "refund"),
                new SagaStep("arrange-shipping", shipping, "arrangeShipment", "cancelShipment")
        ));
    }
}
