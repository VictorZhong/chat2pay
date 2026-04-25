package com.chat2pay.app.application.conversation.tool;

import org.springframework.stereotype.Component;

@Component
public class ConfirmDomesticPaymentTool implements PaymentTool {

    @Override
    public String name() {
        return "confirm_domestic_payment";
    }

    @Override
    public PaymentToolExecution execute(PaymentToolContext context) {
        return context.actions().executeConfirmDomesticPaymentTool(context);
    }
}
