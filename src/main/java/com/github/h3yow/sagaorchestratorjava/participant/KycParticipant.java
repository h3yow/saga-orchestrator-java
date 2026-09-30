package com.github.h3yow.sagaorchestratorjava.participant;

import org.springframework.stereotype.Component;

/** 模拟实名核验参与方。 */
@Component
public class KycParticipant extends AbstractSimulatedParticipant {
    public KycParticipant(SideEffectLedger ledger) {
        super("kyc", ledger);
    }
}
