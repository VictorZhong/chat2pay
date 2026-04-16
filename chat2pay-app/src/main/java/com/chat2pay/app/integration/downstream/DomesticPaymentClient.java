package com.chat2pay.app.integration.downstream;

import com.chat2pay.app.domain.PaymentDraft;
import com.chat2pay.app.domain.Profile;

public interface DomesticPaymentClient {

    PaymentConfirmationResult confirmDomesticPayment(Profile profile, PaymentDraft draft);
}
