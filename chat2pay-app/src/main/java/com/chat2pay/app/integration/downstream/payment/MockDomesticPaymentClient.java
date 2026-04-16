package com.chat2pay.app.integration.downstream.payment;

import com.chat2pay.app.application.journey.DomesticPaymentClient;
import com.chat2pay.app.common.UlidFactory;
import com.chat2pay.app.domain.PaymentConfirmationResult;
import com.chat2pay.app.domain.PaymentDraft;
import com.chat2pay.app.domain.Profile;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "chat2pay.downstream", name = "mock-enabled", havingValue = "true", matchIfMissing = true)
public class MockDomesticPaymentClient implements DomesticPaymentClient {

    private final UlidFactory ulidFactory;

    public MockDomesticPaymentClient(UlidFactory ulidFactory) {
        this.ulidFactory = ulidFactory;
    }

    @Override
    public PaymentConfirmationResult confirmDomesticPayment(Profile profile, PaymentDraft draft) {
        String transferReference = "MOCK-" + ulidFactory.nextUlid();
        return new PaymentConfirmationResult(
                transferReference,
                Map.of(
                        "status", "SUCCESS",
                        "profileUsername", profile.username(),
                        "payeeIdIndex", draft.getPayeeIdIndex(),
                        "amount", draft.getAmount().toPlainString(),
                        "currency", draft.getCurrency(),
                        "transferReference", transferReference));
    }
}
