package com.chat2pay.app.domain;

public enum TransferStatus {
    DRAFT,
    READY_FOR_CONFIRMATION,
    CONFIRMING,
    CONFIRMED,
    FAILED,
    CANCELLED
}
