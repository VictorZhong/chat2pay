package com.chat2pay.app.domain;

public enum WorkflowState {
    IDLE,
    COLLECTING_PAYMENT_DETAILS,
    RESOLVING_PAYEE,
    AWAITING_USER_CONFIRMATION,
    CONFIRMING_PAYMENT,
    COMPLETED,
    FAILED,
    CANCELLED
}
