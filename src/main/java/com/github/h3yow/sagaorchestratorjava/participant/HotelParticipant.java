package com.github.h3yow.sagaorchestratorjava.participant;

import org.springframework.stereotype.Component;

/** 模拟酒店预订参与方（差旅流程使用）。 */
@Component
public class HotelParticipant extends AbstractSimulatedParticipant {
    public HotelParticipant(SideEffectLedger ledger) {
        super("hotel", ledger);
    }
}
