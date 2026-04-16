package com.chat2pay.app.application.journey;

import java.math.BigDecimal;

public record JourneyDraftUpdate(
        String payeeNameInput,
        BigDecimal amount,
        String currency,
        String note,
        String selectedPayeeId) {

    public static JourneyDraftUpdate empty() {
        return new JourneyDraftUpdate(null, null, null, null, null);
    }

    public boolean isEmpty() {
        return payeeNameInput == null
                && amount == null
                && currency == null
                && note == null
                && selectedPayeeId == null;
    }

    public JourneyDraftUpdate mergePreferNonNull(JourneyDraftUpdate fallback) {
        if (fallback == null) {
            return this;
        }

        return new JourneyDraftUpdate(
                payeeNameInput != null && !payeeNameInput.isBlank() ? payeeNameInput : fallback.payeeNameInput,
                amount != null ? amount : fallback.amount,
                currency != null && !currency.isBlank() ? currency : fallback.currency,
                note != null && !note.isBlank() ? note : fallback.note,
                selectedPayeeId != null && !selectedPayeeId.isBlank() ? selectedPayeeId : fallback.selectedPayeeId);
    }
}
