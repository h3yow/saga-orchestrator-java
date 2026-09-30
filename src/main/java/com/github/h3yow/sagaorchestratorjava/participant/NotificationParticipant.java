package com.github.h3yow.sagaorchestratorjava.participant;

import org.springframework.stereotype.Component;

/** 模拟通知（开户结果推送）参与方。 */
@Component
public class NotificationParticipant extends AbstractSimulatedParticipant {
    public NotificationParticipant(SideEffectLedger ledger) {
        super("notification", ledger);
    }
}
