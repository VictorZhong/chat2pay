package com.chat2pay.app.integration.downstream.domestic;

import com.chat2pay.app.api.dto.ChatDtos.DebitAccountSummary;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Client boundary for confirmed domestic payment execution.
 */
public interface DomesticPaymentClient {

    PaymentConfirmationResult confirm(DomesticPaymentRequest request);

    record DomesticPaymentRequest(
            String profileId,
            String payeeIdIndex,
            String payeeName,
            DebitAccountSummary selectedDebitAccount,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            BigDecimal amount,
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
