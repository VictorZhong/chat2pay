package com.chat2pay.app.application.conversation.tool;

import org.springframework.stereotype.Component;

@Component
public class PrepareDomesticPaymentTool implements PaymentTool {

    @Override
    public String name() {
        return "prepare_domestic_payment";
    }

    @Override
    public PaymentToolExecution execute(PaymentToolContext context) {
        return context.actions().executePrepareDomesticPaymentTool(context);
    }
}
