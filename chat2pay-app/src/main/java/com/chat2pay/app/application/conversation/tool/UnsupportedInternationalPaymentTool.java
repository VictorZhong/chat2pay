package com.chat2pay.app.application.conversation.tool;

import org.springframework.stereotype.Component;

@Component
public class UnsupportedInternationalPaymentTool implements PaymentTool {

    @Override
    public String name() {
        return "unsupported_international_payment";
    }

    @Override
    public PaymentToolExecution execute(PaymentToolContext context) {
        return context.actions().executeUnsupportedInternationalPaymentTool(context);
    }
}
