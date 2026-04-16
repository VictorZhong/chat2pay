package com.chat2pay.app.domain;

import java.math.BigDecimal;

public record MessageAnalysis(
        boolean supportedPaymentIntent,
        boolean browsePayeesIntent,
        boolean unsupportedRequest,
        boolean createPayeeIntent,
        boolean confirmIntent,
        boolean cancelIntent,
        boolean changePayeeIntent,
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
                browsePayeesIntent || override.browsePayeesIntent,
                unsupportedRequest || override.unsupportedRequest,
                createPayeeIntent || override.createPayeeIntent,
                confirmIntent || override.confirmIntent,
                cancelIntent || override.cancelIntent,
                changePayeeIntent || override.changePayeeIntent,
                override.payeeName != null && !override.payeeName.isBlank() ? override.payeeName : payeeName,
                override.amount != null ? override.amount : amount,
                override.currency != null && !override.currency.isBlank() ? override.currency : currency,
                override.note != null && !override.note.isBlank() ? override.note : note);
    }
}
