package com.github.h3yow.sagaorchestratorjava.participant;

import org.springframework.stereotype.Component;

/** 模拟账户开立参与方。 */
@Component
public class AccountParticipant extends AbstractSimulatedParticipant {
    public AccountParticipant(SideEffectLedger ledger) {
        super("account", ledger);
    }
}
