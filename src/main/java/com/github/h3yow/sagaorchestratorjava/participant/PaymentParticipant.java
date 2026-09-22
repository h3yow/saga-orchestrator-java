package com.github.h3yow.sagaorchestratorjava.participant;

import org.springframework.stereotype.Component;

/** 模拟支付参与方。 */
@Component
public class PaymentParticipant extends AbstractSimulatedParticipant {
    public PaymentParticipant(SideEffectLedger ledger) {
        super("payment", ledger);
    }
}
