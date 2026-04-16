package com.chat2pay.app.domain;

public record RegisteredPayee(
        String payeeIdIndex,
        String payeeType,
        String name,
        String description) {
}
