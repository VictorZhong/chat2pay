package com.chat2pay.app.domain;

import java.math.BigDecimal;

public record MessageAnalysis(
        boolean supportedPaymentIntent,
        boolean unsupportedRequest,
        boolean confirmIntent,
        boolean cancelIntent,
        String payeeName,
        BigDecimal amount,
        String currency,
        String note) {

    public MessageAnalysis mergePreferNonNull(MessageAnalysis override) {
        if (override == null) {
            return this;
        }

        return new MessageAnalysis(
                supportedPaymentIntent || override.supportedPaymentIntent,
                unsupportedRequest || override.unsupportedRequest,
                confirmIntent || override.confirmIntent,
                cancelIntent || override.cancelIntent,
                override.payeeName != null && !override.payeeName.isBlank() ? override.payeeName : payeeName,
                override.amount != null ? override.amount : amount,
                override.currency != null && !override.currency.isBlank() ? override.currency : currency,
                override.note != null && !override.note.isBlank() ? override.note : note);
    }
}
