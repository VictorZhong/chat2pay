package com.chat2pay.app.domain.conversation;

public enum ConversationState {
    IDLE,
    COLLECTING_DETAILS,
    AWAITING_PAYEE_SELECTION,
    AWAITING_CONFIRMATION,
    EXECUTING,
    COMPLETED,
    FAILED,
    CANCELLED
}
