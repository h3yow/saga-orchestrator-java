package com.github.h3yow.sagaorchestratorjava.core;

import com.github.h3yow.sagaorchestratorjava.participant.AccountParticipant;
import com.github.h3yow.sagaorchestratorjava.participant.CardParticipant;
import com.github.h3yow.sagaorchestratorjava.participant.KycParticipant;
import com.github.h3yow.sagaorchestratorjava.participant.NotificationParticipant;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 构造“账户开立”业务的 Saga 定义（第二种业务流程，与下单流程的步数、参与方组合均不同）：
 * <pre>
 *   1. kyc.verifyIdentity     （实名核验）   补偿 revokeVerification（撤销核验）
 *   2. account.openAccount    （开立账户）   补偿 closeAccount（关闭账户）
 *   3. card.bindCard          （绑定银行卡） 补偿 unbindCard（解绑银行卡）
 *   4. notification.sendWelcome（开户通知）  补偿 recallNotification（撤回通知）
 * </pre>
 * 共四步，参与方组合为 kyc/account/card/notification，与下单流程
 * （inventory/payment/shipping，三步）完全不重叠，可同时并发运行、互不影响。
 */
@Component
public class AccountOpeningSagaFactory implements SagaFactory {

    public static final String BUSINESS_TYPE = "ACCOUNT_OPENING";

    private final KycParticipant kyc;
    private final AccountParticipant account;
    private final CardParticipant card;
    private final NotificationParticipant notification;

    public AccountOpeningSagaFactory(KycParticipant kyc,
                                     AccountParticipant account,
                                     CardParticipant card,
                                     NotificationParticipant notification) {
        this.kyc = kyc;
        this.account = account;
        this.card = card;
        this.notification = notification;
    }

    @Override
    public String businessType() {
        return BUSINESS_TYPE;
    }

    @Override
    public SagaDefinition create() {
        return new SagaDefinition(BUSINESS_TYPE, List.of(
                new SagaStep("verify-identity", kyc, "verifyIdentity", "revokeVerification"),
                new SagaStep("open-account", account, "openAccount", "closeAccount"),
                new SagaStep("bind-card", card, "bindCard", "unbindCard"),
                new SagaStep("send-welcome", notification, "sendWelcome", "recallNotification")
        ));
    }
}
