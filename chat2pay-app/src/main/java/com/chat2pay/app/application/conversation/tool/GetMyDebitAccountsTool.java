package com.chat2pay.app.application.conversation.tool;

import org.springframework.stereotype.Component;

@Component
public class GetMyDebitAccountsTool implements PaymentTool {

    @Override
    public String name() {
        return "get_my_debit_accounts";
    }

    @Override
    public PaymentToolExecution execute(PaymentToolContext context) {
        return context.actions().executeListDebitAccountsTool(context);
    }
}
