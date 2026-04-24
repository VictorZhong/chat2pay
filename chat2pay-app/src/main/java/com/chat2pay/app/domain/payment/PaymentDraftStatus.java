package com.chat2pay.app.domain.payment;

public enum PaymentDraftStatus {
    DRAFT,
    AWAITING_CONFIRMATION,
    EXECUTING,
    CONFIRMED,
    FAILED,
    CANCELLED
}
