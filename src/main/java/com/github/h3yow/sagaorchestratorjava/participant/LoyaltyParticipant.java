package com.github.h3yow.sagaorchestratorjava.participant;

import org.springframework.stereotype.Component;

/** 模拟会员积分参与方（差旅流程使用）。 */
@Component
public class LoyaltyParticipant extends AbstractSimulatedParticipant {
    public LoyaltyParticipant(SideEffectLedger ledger) {
        super("loyalty", ledger);
    }
}
