package com.chat2pay.app.application.conversation.tool;

public interface PaymentTool {

    String name();

    PaymentToolExecution execute(PaymentToolContext context);
}
