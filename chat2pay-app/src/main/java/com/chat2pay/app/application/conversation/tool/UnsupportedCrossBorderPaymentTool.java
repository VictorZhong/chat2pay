package com.chat2pay.app.application.conversation.tool;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class UnsupportedCrossBorderPaymentTool implements PaymentTool {

    @Override
    public String name() {
        return "unsupported_cross_border_payment";
    }

    @Override
    public List<String> aliases() {
        return List.of("unsupported_international_payment");
    }

    @Override
    public PaymentToolExecution execute(PaymentToolContext context) {
        return context.actions().executeUnsupportedCrossBorderPaymentTool(context);
    }
}
