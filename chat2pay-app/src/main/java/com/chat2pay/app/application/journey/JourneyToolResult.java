package com.chat2pay.app.application.journey;

import java.util.Map;

public record JourneyToolResult(
        String toolName,
        boolean success,
        Map<String, Object> payload,
        String errorMessage) {

    public static JourneyToolResult success(String toolName, Map<String, Object> payload) {
        return new JourneyToolResult(toolName, true, payload, null);
    }

    public static JourneyToolResult failure(String toolName, String errorMessage) {
        return new JourneyToolResult(toolName, false, Map.of(), errorMessage);
    }
}
