package com.chat2pay.app.domain;

import java.util.Map;

public record PaymentConfirmationResult(
        String transferReference,
        Map<String, Object> downstreamPayload) {
}
