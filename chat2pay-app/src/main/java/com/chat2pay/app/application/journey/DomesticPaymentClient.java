package com.chat2pay.app.application.journey;

import com.chat2pay.app.domain.PaymentConfirmationResult;
import com.chat2pay.app.domain.PaymentDraft;
import com.chat2pay.app.domain.Profile;

public interface DomesticPaymentClient {

    PaymentConfirmationResult confirmDomesticPayment(Profile profile, PaymentDraft draft);
}
