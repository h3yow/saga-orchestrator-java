package com.github.h3yow.sagaorchestratorjava.participant;

import org.springframework.stereotype.Component;

/** 模拟库存参与方。 */
@Component
public class InventoryParticipant extends AbstractSimulatedParticipant {
    public InventoryParticipant(SideEffectLedger ledger) {
        super("inventory", ledger);
    }
}
