package com.github.h3yow.sagaorchestratorjava.participant;

import org.springframework.stereotype.Component;

/** 模拟银行卡绑定参与方。 */
@Component
public class CardParticipant extends AbstractSimulatedParticipant {
    public CardParticipant(SideEffectLedger ledger) {
        super("card", ledger);
    }
}
