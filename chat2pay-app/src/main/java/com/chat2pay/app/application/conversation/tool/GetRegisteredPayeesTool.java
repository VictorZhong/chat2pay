package com.chat2pay.app.application.conversation.tool;

import org.springframework.stereotype.Component;

@Component
public class GetRegisteredPayeesTool implements PaymentTool {

    @Override
    public String name() {
        return "get_registered_payees";
    }

    @Override
    public PaymentToolExecution execute(PaymentToolContext context) {
        return context.actions().executeRegisteredPayeesTool(context);
    }
}
