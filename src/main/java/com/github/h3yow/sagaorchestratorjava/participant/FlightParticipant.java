package com.github.h3yow.sagaorchestratorjava.participant;

import org.springframework.stereotype.Component;

/** 模拟机票预订参与方（差旅流程使用）。 */
@Component
public class FlightParticipant extends AbstractSimulatedParticipant {
    public FlightParticipant(SideEffectLedger ledger) {
        super("flight", ledger);
    }
}
