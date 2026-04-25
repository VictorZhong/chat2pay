package com.chat2pay.app.application.conversation.tool;

import org.springframework.stereotype.Component;

@Component
public class UnknownPaymentTool implements PaymentTool {

    @Override
    public String name() {
        return "__unknown_payment_tool";
    }

    @Override
    public PaymentToolExecution execute(PaymentToolContext context) {
        return context.actions().executeUnknownPaymentTool(context);
    }
}
