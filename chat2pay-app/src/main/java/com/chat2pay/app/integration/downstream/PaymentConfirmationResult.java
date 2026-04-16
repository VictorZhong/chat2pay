package com.chat2pay.app.integration.downstream;

import java.util.Map;

public record PaymentConfirmationResult(
        String transferReference,
        Map<String, Object> downstreamPayload) {
}
