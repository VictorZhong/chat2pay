package com.chat2pay.app.application.conversation.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PaymentToolRegistry {

    private final Map<String, PaymentTool> tools;
    private final UnknownPaymentTool unknownPaymentTool;

    public PaymentToolRegistry(List<PaymentTool> tools, UnknownPaymentTool unknownPaymentTool) {
        this.tools = tools.stream().collect(Collectors.toUnmodifiableMap(
                PaymentTool::name,
                Function.identity()
        ));
        this.unknownPaymentTool = unknownPaymentTool;
    }

    public PaymentToolExecution execute(PaymentToolContext context) {
        return tools.getOrDefault(context.toolCall().name(), unknownPaymentTool).execute(context);
    }
}
