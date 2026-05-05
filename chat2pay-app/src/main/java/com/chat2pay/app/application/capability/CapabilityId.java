package com.chat2pay.app.application.capability;

public enum CapabilityId {
    LIST_DEBIT_ACCOUNTS("listDebitAccounts"),
    LIST_PAYEES("listPayees"),
    PREPARE_DOMESTIC_PAYMENT("prepareDomesticPayment"),
    CONFIRM_DOMESTIC_PAYMENT("confirmDomesticPayment"),
    CANCEL_PAYMENT("cancelPayment"),
    UNSUPPORTED_CROSS_BORDER_PAYMENT("unsupportedCrossBorderPayment");

    private final String wireName;

    CapabilityId(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
