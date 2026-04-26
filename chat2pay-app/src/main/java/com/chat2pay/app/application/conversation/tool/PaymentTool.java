package com.chat2pay.app.application.conversation.tool;

import java.util.List;

public interface PaymentTool {

    String name();

    default List<String> aliases() {
        return List.of();
    }

    PaymentToolExecution execute(PaymentToolContext context);
}
