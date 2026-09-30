package com.github.h3yow.sagaorchestratorjava.participant;

import org.springframework.stereotype.Component;

/** 模拟签证办理参与方（差旅流程使用）。 */
@Component
public class VisaParticipant extends AbstractSimulatedParticipant {
    public VisaParticipant(SideEffectLedger ledger) {
        super("visa", ledger);
    }
}
