package com.chat2pay.app.integration.downstream;

public record RegisteredPayee(
        String payeeIdIndex,
        String payeeType,
        String name,
        String description) {
}
