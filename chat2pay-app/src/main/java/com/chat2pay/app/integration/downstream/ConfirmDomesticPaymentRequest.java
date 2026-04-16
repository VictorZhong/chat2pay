package com.chat2pay.app.integration.downstream;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Map;

public record ConfirmDomesticPaymentRequest(
        DebitAccount debitAccount,
        TransactionAmount transactionAmount,
        TransactionSchedule transactionSchedule,
        Map<String, Object> transactionMemo,
        String payeeType,
        @JsonProperty("pyeeIdIndex") String payeeIdIndex,
        boolean payeeSuspiciousIndicator,
        CreditAmount creditAmount) {

    public record DebitAccount(
            DebitAccountIdentifier debitAccountIdentifier,
            String currency) {
    }

    public record DebitAccountIdentifier(
            String acn,
            String productCategoryCode) {
    }

    public record TransactionAmount(
            BigDecimal amount,
            String currencyCode) {
    }

    public record TransactionSchedule(
            String scheduleType,
            String scheduledDate) {
    }

    public record CreditAmount(String currencyCode) {
    }
}
