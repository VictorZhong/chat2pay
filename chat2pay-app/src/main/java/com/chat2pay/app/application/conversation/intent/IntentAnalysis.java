package com.chat2pay.app.application.conversation.intent;

import java.math.BigDecimal;
import java.time.LocalDate;

public record IntentAnalysis(
        IntentType intent,
        String toolName,
        String payeeQuery,
        BigDecimal amount,
        LocalDate paymentDate,
        String source
) {
    public static IntentAnalysis unknown(String source) {
        return new IntentAnalysis(IntentType.UNKNOWN, null, null, null, null, source);
    }

    public IntentAnalysis withSource(String nextSource) {
        return new IntentAnalysis(intent, toolName, payeeQuery, amount, paymentDate, nextSource);
    }

    public IntentAnalysis mergeMissingSlotsFrom(IntentAnalysis fallback) {
        if (fallback == null) return this;
        IntentType resolvedIntent = intent == IntentType.UNKNOWN && fallback.intent() != IntentType.UNKNOWN
                ? fallback.intent()
                : intent;
        String resolvedToolName = toolName != null ? toolName : toolNameFor(resolvedIntent);
        return new IntentAnalysis(
                resolvedIntent,
                resolvedToolName,
                firstNonBlank(payeeQuery, fallback.payeeQuery()),
                amount != null ? amount : fallback.amount(),
                paymentDate != null ? paymentDate : fallback.paymentDate(),
                source
        );
    }

    public static String toolNameFor(IntentType intent) {
        return switch (intent) {
            case DOMESTIC_PAYMENT -> "prepare_domestic_payment";
            case PAYEE_LOOKUP -> "get_registered_payees";
            case CONFIRM_PAYMENT -> "confirm_domestic_payment";
            case CANCEL_PAYMENT -> "cancel_payment";
            case CROSS_BORDER_PAYMENT -> "unsupported_cross_border_payment";
            case UNKNOWN -> null;
        };
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second != null && !second.isBlank() ? second : null;
    }
}
