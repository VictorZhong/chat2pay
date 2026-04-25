package com.chat2pay.app.application.conversation.tool;

import org.springframework.stereotype.Component;

@Component
public class CancelPaymentTool implements PaymentTool {

    @Override
    public String name() {
        return "cancel_payment";
    }

    @Override
    public PaymentToolExecution execute(PaymentToolContext context) {
        return context.actions().executeCancelPaymentTool(context);
    }
}
