package com.chat2pay.app.application.conversation.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(PaymentToolRegistry.class);

    private final Map<String, PaymentTool> tools;
    private final UnknownPaymentTool unknownPaymentTool;

    public PaymentToolRegistry(List<PaymentTool> tools, UnknownPaymentTool unknownPaymentTool) {
        Map<String, PaymentTool> mapped = new LinkedHashMap<>();
        for (PaymentTool tool : tools) {
            register(mapped, tool.name(), tool);
            for (String alias : tool.aliases()) {
                register(mapped, alias, tool);
            }
        }
        this.tools = Map.copyOf(mapped);
        this.unknownPaymentTool = unknownPaymentTool;
    }

    public PaymentToolExecution execute(PaymentToolContext context) {
        return tools.getOrDefault(context.toolCall().name(), unknownPaymentTool).execute(context);
    }

    private void register(Map<String, PaymentTool> mapped, String name, PaymentTool tool) {
        PaymentTool existing = mapped.putIfAbsent(name, tool);
        if (existing != null && existing != tool) {
            log.warn("Payment tool name collision ignored: name={} existing={} ignored={}",
                    name, existing.getClass().getSimpleName(), tool.getClass().getSimpleName());
        }
    }
}
