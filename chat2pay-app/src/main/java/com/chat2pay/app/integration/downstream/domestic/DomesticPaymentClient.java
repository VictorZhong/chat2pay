package com.chat2pay.app.integration.downstream.domestic;

import java.time.LocalDate;
import java.util.Map;

/**
 * Client boundary for confirmed domestic payment execution.
 */
public interface DomesticPaymentClient {

    PaymentConfirmationResult confirm(DomesticPaymentRequest request);

    record DomesticPaymentRequest(
            String payeeIdIndex,
            String payeeName,
            Double amount,
            LocalDate paymentDate
    ) {}

    record PaymentConfirmationResult(
            boolean ok,
            String reference,
            int statusCode,
            Map<String, Object> response,
            String message
    ) {}
}
