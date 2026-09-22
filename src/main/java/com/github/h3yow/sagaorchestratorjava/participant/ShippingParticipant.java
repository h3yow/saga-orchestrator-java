package com.github.h3yow.sagaorchestratorjava.participant;

import org.springframework.stereotype.Component;

/** 模拟发货参与方。 */
@Component
public class ShippingParticipant extends AbstractSimulatedParticipant {
    public ShippingParticipant(SideEffectLedger ledger) {
        super("shipping", ledger);
    }
}
